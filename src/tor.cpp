#include "tor.h"
#include "vault.h"

#include <QCoreApplication>
#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QUrl>
#include <cstdio>
#include <memory>

namespace
{
Tor *s_instance = nullptr;
constexpr int kMaxConns = 64;
constexpr int kRequestMs = 30000;
constexpr int kMaxRestarts = 5;
}

Tor::Tor(Vault *vault, QObject *parent, const QString &dataDir)
    : QObject(parent)
    , m_vault(vault)
    , m_dataDir(dataDir)
{
    s_instance = this;
    // Everything Tor itself talks to is on this machine: never through any proxy.
    m_server.setProxy(QNetworkProxy::NoProxy);
    connect(&m_server, &QTcpServer::newConnection, this, &Tor::onConnection);
    m_portPoll.setInterval(100);
    connect(&m_portPoll, &QTimer::timeout, this, &Tor::pollPortFile);
    // Bootstrap events also arrive by themselves; asking every second covers a missed one.
    m_bootPoll.setInterval(1000);
    connect(&m_bootPoll, &QTimer::timeout, this, [this] {
        if (!m_authed)
            return;
        if (m_socksPort == 0)
            querySocks();
        if (m_progress < 100)
            command("GETINFO status/bootstrap-phase", [this](bool ok, const QList<QByteArray> &lines) {
                if (ok)
                    for (const QByteArray &l : lines)
                        readBootstrap(l);
            });
    });
    if (vault) {
        connect(vault, &Vault::opened, this, &Tor::reloadKey);
        loadKey();
    } else {
        m_keyLoaded = true;   // a throwaway address, made by Tor
    }
    if (QCoreApplication *app = QCoreApplication::instance())
        connect(app, &QCoreApplication::aboutToQuit, this, &Tor::stop);
}

Tor::~Tor()
{
    stop();
    // Close the onion service's connections while everything they call back into still exists.
    const QList<QTcpSocket *> conns = m_conns.keys();
    m_conns.clear();
    for (QTcpSocket *s : conns) {
        QObject::disconnect(s, nullptr, this, nullptr);
        s->abort();
        delete s;
    }
    m_server.close();
    if (s_instance == this)
        s_instance = nullptr;
}

Tor *Tor::instance()
{
    return s_instance;
}

// The one place that knows where tor is.
QString Tor::binary()
{
#ifdef Q_OS_WIN
    const QString exe = QCoreApplication::applicationDirPath() + QStringLiteral("/tor.exe");
    return QFileInfo(exe).isExecutable() ? exe : QString();
#else
    const QStringList places{QStringLiteral("/app/bin/tor"), QCoreApplication::applicationDirPath() + QStringLiteral("/tor")};
    for (const QString &p : places)
        if (QFileInfo(p).isFile() && QFileInfo(p).isExecutable())
            return p;
    return QStandardPaths::findExecutable(QStringLiteral("tor"));   // a development build
#endif
}

QString Tor::dataDir() const
{
    if (!m_dataDir.isEmpty())
        return m_dataDir;
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/tor");
}

bool Tor::validOnion(const QString &onion)
{
    static const QRegularExpression re(QStringLiteral("^[a-z2-7]{56}(\\.onion)?$"));
    return re.match(onion).hasMatch();
}

QNetworkProxy Tor::proxy(const QString &isolation) const
{
    // Port 0 while not ready: a connection that uses it anyway fails, it never goes out directly.
    QNetworkProxy p(QNetworkProxy::Socks5Proxy, QStringLiteral("127.0.0.1"), quint16(m_ready ? m_socksPort : 0));
    // Host names go to Tor as names (SOCKS5 domain requests); nothing is looked up on this side.
    p.setCapabilities(QNetworkProxy::TunnelingCapability | QNetworkProxy::HostNameLookupCapability);
    if (!isolation.isEmpty()) {
        p.setUser(isolation.left(200));
        p.setPassword(QStringLiteral("huginn"));
    }
    return p;
}

// ------------------------------------------------------------------ the tor process

QString Tor::quoted(const QString &path)
{
    QString s = QDir::toNativeSeparators(path);
    s.replace(QLatin1Char('\\'), QStringLiteral("\\\\"));
    s.replace(QLatin1Char('"'), QStringLiteral("\\\""));
    return QLatin1Char('"') + s + QLatin1Char('"');
}

bool Tor::writeTorrc(const QString &path) const
{
    const QString dir = dataDir();
    const QStringList lines{
        QStringLiteral("# Written by Huginn every time it starts Tor. Changes here are replaced."),
        QStringLiteral("DataDirectory ") + quoted(dir),
        QStringLiteral("SocksPort auto"),              // 127.0.0.1, a free port
        QStringLiteral("SafeSocks 1"),                 // refuse requests that carry an IP address (a local DNS lookup happened)
        QStringLiteral("ControlPort auto"),            // 127.0.0.1, a free port
        QStringLiteral("ControlPortWriteToFile ") + quoted(dir + QStringLiteral("/control-port")),
        QStringLiteral("CookieAuthentication 1"),
        QStringLiteral("CookieAuthFile ") + quoted(dir + QStringLiteral("/control_auth_cookie")),
        QStringLiteral("ClientOnly 1"),
        QStringLiteral("AvoidDiskWrites 1"),
        QStringLiteral("Log notice stdout"),
    };
    QSaveFile f(path);
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(lines.join(QLatin1Char('\n')).toUtf8() + '\n');
    return f.commit();
}

void Tor::start()
{
    m_wanted = true;
    if (!m_server.isListening() && !m_server.listen(QHostAddress::LocalHost, 0))
        qWarning("tor: couldn't open the local listener for the onion service");
    if (m_proc)
        return;
    m_restarts = 0;
    launch();
}

void Tor::launch()
{
    setState(QStringLiteral("starting"));
    const QString dir = dataDir();
    QDir().mkpath(dir);
    QFile::setPermissions(dir, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner);
    QFile::remove(dir + QStringLiteral("/control-port"));
    const QString torrc = dir + QStringLiteral("/torrc");
    const QString exe = binary();
    if (exe.isEmpty() || !writeTorrc(torrc)) {
        qWarning("tor: %s", exe.isEmpty() ? "the tor program wasn't found" : "couldn't write the torrc");
        setState(QStringLiteral("failed"));
        return;
    }
    auto *p = new QProcess(this);
    m_proc = p;
    p->setProcessChannelMode(QProcess::MergedChannels);
    p->setWorkingDirectory(dir);
    connect(p, &QProcess::readyReadStandardOutput, this, [this, p] {
        m_logBuf += p->readAllStandardOutput();
        int nl;
        while ((nl = m_logBuf.indexOf('\n')) >= 0) {
            const QString line = QString::fromUtf8(m_logBuf.left(nl)).trimmed();
            m_logBuf.remove(0, nl + 1);
            if (line.isEmpty())
                continue;
            m_log << line;
            while (m_log.size() > 40)
                m_log.removeFirst();
            if (line.contains(QLatin1String("[warn]")) || line.contains(QLatin1String("[err]")))
                qWarning("tor: %s", qPrintable(line));
        }
        if (m_logBuf.size() > 64 * 1024)
            m_logBuf.clear();
    });
    connect(p, &QProcess::finished, this, [this, p](int, QProcess::ExitStatus) {
        if (p == m_proc)
            onProcessGone();
    });
    connect(p, &QProcess::errorOccurred, this, [this, p](QProcess::ProcessError e) {
        if (e != QProcess::FailedToStart || p != m_proc)
            return;
        qWarning("tor: couldn't start %s", qPrintable(binary()));
        m_restarts = kMaxRestarts;   // trying again won't help
        onProcessGone();
    });
    // Tor watches this process too: if Huginn dies without stopping it, it exits by itself.
    p->start(exe, {QStringLiteral("-f"), torrc, QStringLiteral("__OwningControllerProcess"),
                   QString::number(QCoreApplication::applicationPid())});
    m_portTries = 0;
    m_portPoll.start();
}

void Tor::onProcessGone()
{
    QProcess *p = m_proc;
    m_proc = nullptr;
    m_portPoll.stop();
    if (p) {
        QObject::disconnect(p, nullptr, this, nullptr);
        p->deleteLater();
    }
    resetControl();
    m_socksPort = 0;
    m_onionAdded = false;
    if (m_published) {
        m_published = false;
        Q_EMIT onionChanged();
    }
    setProgress(0);
    updateReady();
    if (!m_wanted) {
        setState(QStringLiteral("off"));
        return;
    }
    if (m_restarts >= kMaxRestarts) {
        setState(QStringLiteral("failed"));
        return;
    }
    ++m_restarts;
    setState(QStringLiteral("starting"));
    QTimer::singleShot(3000 * m_restarts, this, [this] {
        if (m_wanted && !m_proc)
            launch();
    });
}

void Tor::stop()
{
    m_wanted = false;
    m_portPoll.stop();
    QProcess *p = m_proc;
    m_proc = nullptr;
    // With TAKEOWNERSHIP, closing the control connection already makes tor exit.
    resetControl();
    if (p) {
        QObject::disconnect(p, nullptr, this, nullptr);
        p->terminate();
        if (!p->waitForFinished(3000)) {
            p->kill();
            p->waitForFinished(1000);
        }
        delete p;
    }
    m_socksPort = 0;
    m_onionAdded = false;
    if (m_published) {
        m_published = false;
        Q_EMIT onionChanged();
    }
    setProgress(0);
    updateReady();
    setState(QStringLiteral("off"));
}

void Tor::pollPortFile()
{
    if (!m_proc) {
        m_portPoll.stop();
        return;
    }
    const QString dir = dataDir();
    QFile f(dir + QStringLiteral("/control-port"));
    if (f.open(QIODevice::ReadOnly)) {
        const QByteArray s = f.readAll().trimmed();   // PORT=127.0.0.1:43215
        const int colon = s.lastIndexOf(':');
        const int port = s.startsWith("PORT=") && colon > 0 ? s.mid(colon + 1).toInt() : 0;
        if (port > 0 && port < 65536 && QFileInfo(dir + QStringLiteral("/control_auth_cookie")).size() == 32) {
            m_portPoll.stop();
            m_controlPort = port;
            authenticate();
            return;
        }
    }
    if (++m_portTries > 600) {   // a minute without a control port: start over
        m_portPoll.stop();
        qWarning("tor: no control port after a minute");
        m_proc->kill();
    }
}

// ------------------------------------------------------------------ control port

void Tor::resetControl()
{
    m_bootPoll.stop();
    if (QTcpSocket *s = m_control) {
        m_control = nullptr;
        QObject::disconnect(s, nullptr, this, nullptr);
        s->abort();
        s->deleteLater();
    }
    m_controlBuf.clear();
    m_commands.clear();
    m_reply.clear();
    m_event.clear();
    m_inData = false;
    m_dataIsEvent = false;
    m_authed = false;
}

void Tor::authenticate()
{
    resetControl();
    auto *s = new QTcpSocket(this);
    m_control = s;
    s->setProxy(QNetworkProxy::NoProxy);
    connect(s, &QTcpSocket::connected, this, [this, s] {
        if (s != m_control)
            return;
        QFile c(dataDir() + QStringLiteral("/control_auth_cookie"));
        const QByteArray cookie = c.open(QIODevice::ReadOnly) ? c.readAll() : QByteArray();
        command("AUTHENTICATE " + cookie.toHex(), [this](bool ok, const QList<QByteArray> &) {
            if (ok) {
                afterAuth();
            } else {
                qWarning("tor: the control port refused the cookie");
                if (m_proc)
                    m_proc->kill();   // onProcessGone starts it again
            }
        });
    });
    connect(s, &QTcpSocket::readyRead, this, &Tor::onControlData);
    const auto lost = [this, s] {
        if (s != m_control)
            return;
        resetControl();
        // Tor closes the control connection when it exits; if it's still running, start over.
        if (m_proc)
            m_proc->kill();
    };
    connect(s, &QTcpSocket::disconnected, this, lost);
    connect(s, &QTcpSocket::errorOccurred, this, lost);
    s->connectToHost(QHostAddress::LocalHost, quint16(m_controlPort));
}

void Tor::command(const QByteArray &line, std::function<void(bool, const QList<QByteArray> &)> done)
{
    if (!m_control)
        return;
    m_commands.enqueue(Command{line, std::move(done)});
    m_control->write(line + "\r\n");
}

void Tor::onControlData()
{
    QTcpSocket *s = m_control;
    if (!s)
        return;
    m_controlBuf += s->readAll();
    if (m_controlBuf.size() > 4 * 1024 * 1024) {
        resetControl();
        if (m_proc)
            m_proc->kill();
        return;
    }
    int nl;
    while (m_control == s && (nl = m_controlBuf.indexOf("\r\n")) >= 0) {
        const QByteArray line = m_controlBuf.left(nl);
        m_controlBuf.remove(0, nl + 2);
        onControlLine(line);
    }
}

// Replies are "250-a", "250+b" (data lines up to "."), "250 OK"; events the same with 650.
void Tor::onControlLine(const QByteArray &line)
{
    if (m_inData) {
        QList<QByteArray> &into = m_dataIsEvent ? m_event : m_reply;
        if (line == ".") {
            m_inData = false;
        } else if (!into.isEmpty()) {
            into.last() += '\n' + (line.startsWith("..") ? line.mid(1) : line);
        }
        return;
    }
    if (line.size() < 4)
        return;
    const QByteArray code = line.left(3);
    const char sep = line.at(3);
    const bool event = code == "650";
    QList<QByteArray> &into = event ? m_event : m_reply;
    into << line.mid(4);
    if (sep == '+') {
        m_inData = true;
        m_dataIsEvent = event;
        return;
    }
    if (sep == '-')
        return;
    if (event) {
        const QList<QByteArray> lines = m_event;
        m_event.clear();
        onEvent(lines);
        return;
    }
    const QList<QByteArray> lines = m_reply;
    m_reply.clear();
    if (m_commands.isEmpty())
        return;
    const Command c = m_commands.dequeue();
    if (c.done)
        c.done(code.startsWith('2'), lines);
}

void Tor::onEvent(const QList<QByteArray> &lines)
{
    const QByteArray first = lines.value(0);
    if (first.startsWith("STATUS_CLIENT")) {
        readBootstrap(first);
    } else if (first.startsWith("HS_DESC ")) {
        // HS_DESC UPLOADED <address> <auth> <hsdir> ...
        const QList<QByteArray> f = first.split(' ');
        if (f.value(1) == "UPLOADED" && !m_onion.isEmpty() && f.value(2) == m_onion.toLatin1() && !m_published) {
            m_published = true;
            Q_EMIT onionChanged();
        }
    }
}

void Tor::readBootstrap(const QByteArray &text)
{
    if (!text.contains("BOOTSTRAP"))
        return;
    static const QRegularExpression re(QStringLiteral("PROGRESS=(\\d+)"));
    const QRegularExpressionMatch m = re.match(QString::fromLatin1(text));
    if (m.hasMatch())
        setProgress(qBound(0, m.captured(1).toInt(), 100));
}

void Tor::querySocks()
{
    command("GETINFO net/listeners/socks", [this](bool ok, const QList<QByteArray> &lines) {
        if (!ok)
            return;
        for (const QByteArray &l : lines) {
            if (!l.startsWith("net/listeners/socks="))
                continue;
            QByteArray v = l.mid(int(qstrlen("net/listeners/socks="))).trimmed();
            v.replace('"', "");
            const QByteArray firstAddr = v.split(' ').value(0);   // 127.0.0.1:39745
            const int port = firstAddr.mid(firstAddr.lastIndexOf(':') + 1).toInt();
            if (firstAddr.startsWith("127.0.0.1:") && port > 0 && port < 65536) {
                m_socksPort = port;
                updateReady();
            }
        }
    });
}

void Tor::afterAuth()
{
    m_authed = true;
    command("TAKEOWNERSHIP");
    command("SETEVENTS STATUS_CLIENT HS_DESC");
    querySocks();
    command("GETINFO status/bootstrap-phase", [this](bool ok, const QList<QByteArray> &lines) {
        if (ok)
            for (const QByteArray &l : lines)
                readBootstrap(l);
    });
    m_bootPoll.start();
    loadKey();
    addOnion();
}

// ------------------------------------------------------------------ onion service

void Tor::loadKey()
{
    if (m_keyLoaded || !m_vault || m_vault->locked())
        return;
    const QByteArray raw = m_vault->read(keyName());
    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    const QByteArray key = o.value(QStringLiteral("key")).toString().toLatin1();
    const QString id = o.value(QStringLiteral("id")).toString();
    QString onion;
    if (key.startsWith("ED25519-V3:") && key.size() > 20 && validOnion(id) && !id.endsWith(QLatin1String(".onion"))) {
        m_key = key;
        onion = id;
    } else {
        m_key.clear();
        const QString path = m_vault->path(keyName());
        if (QFile::exists(path)) {
            // Can't be read (damaged): keep it aside and make a new address rather than none at all.
            qWarning("tor: the onion key can't be read; making a new address");
            QFile::remove(path + QStringLiteral(".damaged"));
            QFile::rename(path, path + QStringLiteral(".damaged"));
        }
    }
    m_keyLoaded = true;
    if (onion != m_onion) {
        m_onion = onion;
        Q_EMIT onionChanged();
    }
}

// The Vault opened (first unlock, or a new lock after everything was erased): read the key again.
void Tor::reloadKey()
{
    const QString was = m_onion;
    m_keyLoaded = false;
    loadKey();
    if (m_onionAdded && (m_key.isEmpty() || m_onion != was)) {
        // The address being served is no longer this device's: take it down, serve the right one.
        if (validOnion(was))
            command("DEL_ONION " + was.toLatin1());
        m_onionAdded = false;
        if (m_published) {
            m_published = false;
            Q_EMIT onionChanged();
        }
    }
    addOnion();
}

void Tor::addOnion()
{
    if (!m_authed || m_onionAdded || !m_keyLoaded || !m_server.isListening())
        return;
    if (m_vault && m_vault->locked())
        return;
    const QByteArray target = " Port=80,127.0.0.1:" + QByteArray::number(m_server.serverPort());
    const bool fresh = m_key.isEmpty();
    // A new key the first time (Tor hands back the private key to keep), then always the kept one.
    const QByteArray line = fresh ? "ADD_ONION NEW:ED25519-V3" + QByteArray(m_vault ? "" : " Flags=DiscardPK") + target
                                  : "ADD_ONION " + m_key + " Flags=DiscardPK" + target;
    m_onionAdded = true;
    QPointer<QTcpSocket> control = m_control;
    command(line, [this, fresh, control](bool ok, const QList<QByteArray> &lines) {
        if (!ok) {
            m_onionAdded = false;
            qWarning("tor: couldn't add the onion service (%s)", lines.value(0).left(120).constData());
            QTimer::singleShot(10000, this, [this, control] {
                if (control && control == m_control)
                    addOnion();
            });
            return;
        }
        QString id;
        QByteArray key;
        for (const QByteArray &l : lines) {
            if (l.startsWith("ServiceID="))
                id = QString::fromLatin1(l.mid(10)).trimmed();
            else if (l.startsWith("PrivateKey="))
                key = l.mid(11).trimmed();
        }
        if (fresh && m_vault && key.startsWith("ED25519-V3:") && validOnion(id)) {
            m_key = key;
            const QJsonObject o{{QStringLiteral("id"), id}, {QStringLiteral("key"), QString::fromLatin1(key)}};
            if (!m_vault->write(keyName(), QJsonDocument(o).toJson(QJsonDocument::Compact)))
                qWarning("tor: couldn't save the onion key; the address will change next start");
        }
        if (validOnion(id) && id != m_onion) {
            m_onion = id;
            Q_EMIT onionChanged();
        }
    });
}

// ------------------------------------------------------------------ state

void Tor::setState(const QString &state)
{
    if (m_state == state)
        return;
    m_state = state;
    Q_EMIT stateChanged();
}

void Tor::setProgress(int p)
{
    if (m_progress == p)
        return;
    m_progress = p;
    Q_EMIT progressChanged();
    updateReady();
}

void Tor::updateReady()
{
    const bool r = m_proc && m_authed && m_socksPort > 0 && m_progress >= 100;
    if (r == m_ready)
        return;
    m_ready = r;
    if (r) {
        m_restarts = 0;
        setState(QStringLiteral("ready"));
    } else if (m_wanted && m_state == QLatin1String("ready")) {
        setState(QStringLiteral("starting"));
    }
    Q_EMIT readyChanged();
}

// ------------------------------------------------------------------ sending through Tor

void Tor::post(const QString &onion, const QString &path, const QByteArray &body, std::function<void(int)> done)
{
    QString id = onion.trimmed().toLower();
    if (id.endsWith(QLatin1String(".onion")))
        id.chop(6);
    static const QRegularExpression badPath(QStringLiteral("[\\s\\x00-\\x1f#]"));
    const bool pathOk = path.startsWith(QLatin1Char('/')) && !path.startsWith(QLatin1String("//")) && !badPath.match(path).hasMatch();
    const QUrl url(QStringLiteral("http://") + id + QStringLiteral(".onion") + path);
    if (!m_ready || !validOnion(id) || !pathOk || !url.isValid() || url.host() != id + QStringLiteral(".onion")) {
        QTimer::singleShot(0, this, [done] {
            if (done)
                done(-1);
        });
        return;
    }
    QNetworkRequest req(url);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Huginn"));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    req.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    req.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
    req.setTransferTimeout(kRequestMs);
    m_net.setProxy(proxy());
    QNetworkReply *r = m_net.post(req, body);
    // 30 s in all, not only 30 s without progress.
    QTimer::singleShot(kRequestMs, r, [r] {
        if (r->isRunning())
            r->abort();
    });
    connect(r, &QNetworkReply::finished, this, [r, done] {
        r->deleteLater();
        const QVariant st = r->attribute(QNetworkRequest::HttpStatusCodeAttribute);
        const int code = st.isValid() && st.toInt() > 0 ? st.toInt() : -1;
        if (done)
            done(code);
    });
}

// ------------------------------------------------------------------ receiving (onion service)

void Tor::onConnection()
{
    while (QTcpSocket *s = m_server.nextPendingConnection()) {
        if (!s->peerAddress().isLoopback() || m_conns.size() >= kMaxConns) {
            s->abort();
            s->deleteLater();
            continue;
        }
        m_conns.insert(s, Conn());
        connect(s, &QTcpSocket::readyRead, this, [this, s] { onHttpData(s); });
        connect(s, &QTcpSocket::disconnected, this, [this, s] {
            m_conns.remove(s);
            s->deleteLater();
        });
        QTimer::singleShot(kRequestMs, s, [s] { s->abort(); });   // a sender that never finishes
        if (s->bytesAvailable() > 0)
            QTimer::singleShot(0, s, [this, s] { onHttpData(s); });
    }
}

// The same limits as the Wi-Fi endpoint (LocalSend): 64 KiB of headers, 8 MiB of body.
void Tor::onHttpData(QTcpSocket *s)
{
    auto it = m_conns.find(s);
    if (it == m_conns.end())
        return;
    Conn &c = *it;
    if (c.replied) {
        s->readAll();
        return;
    }
    if (!c.headDone) {
        c.head += s->readAll();
        const int end = int(c.head.indexOf("\r\n\r\n"));
        if (end < 0) {
            if (c.head.size() > MaxHead)
                reply(s, 431);
            return;
        }
        if (end > MaxHead) {
            reply(s, 431);
            return;
        }
        c.headDone = true;
        const QList<QByteArray> lines = c.head.left(end).split('\n');
        const QList<QByteArray> first = lines.value(0).trimmed().split(' ');
        const QByteArray method = first.value(0);
        QByteArray target = first.value(1);
        const int q = int(target.indexOf('?'));
        if (q >= 0)
            target.truncate(q);
        QHash<QByteArray, QByteArray> headers;
        for (int i = 1; i < lines.size(); ++i) {
            const int colon = int(lines.at(i).indexOf(':'));
            if (colon > 0)
                headers.insert(lines.at(i).left(colon).trimmed().toLower(), lines.at(i).mid(colon + 1).trimmed());
        }
        if (target != MessagePath) {
            reply(s, 404);
            return;
        }
        if (method != "POST") {
            reply(s, 405, "Allow: POST\r\n");
            return;
        }
        if (headers.contains("transfer-encoding")) {
            reply(s, 411);   // a length is required, like the Wi-Fi endpoint reads it
            return;
        }
        bool okLen = false;
        const qint64 len = headers.value("content-length").toLongLong(&okLen);
        if (!okLen || len < 0) {
            reply(s, 411);
            return;
        }
        if (len > MaxBody) {
            reply(s, 413);
            return;
        }
        if (headers.value("expect").toLower() == "100-continue")
            s->write("HTTP/1.1 100 Continue\r\n\r\n");
        c.left = len;
        c.body = c.head.mid(end + 4);
        c.head.clear();
    } else {
        c.body += s->readAll();
    }
    if (c.body.size() < c.left)
        return;
    QByteArray body = c.body.left(c.left);
    c.body.clear();
    c.replied = true;   // set before the handler runs: it may add or drop other connections
    int code = m_handler ? m_handler(body) : 503;
    if (code < 100 || code > 599)
        code = 500;
    reply(s, code);
}

void Tor::reply(QTcpSocket *s, int code, const QByteArray &extra)
{
    if (!s || !m_conns.contains(s))
        return;
    m_conns[s].replied = true;
    QByteArray reason = "OK";
    switch (code) {
    case 204: reason = "No Content"; break;
    case 400: reason = "Bad Request"; break;
    case 401: reason = "Unauthorized"; break;
    case 403: reason = "Forbidden"; break;
    case 404: reason = "Not Found"; break;
    case 405: reason = "Method Not Allowed"; break;
    case 409: reason = "Conflict"; break;
    case 411: reason = "Length Required"; break;
    case 413: reason = "Payload Too Large"; break;
    case 422: reason = "Unprocessable Entity"; break;
    case 431: reason = "Request Header Fields Too Large"; break;
    case 500: reason = "Internal Server Error"; break;
    case 503: reason = "Service Unavailable"; break;
    default: if (code >= 400) reason = "Error"; break;
    }
    const QByteArray body = "{}";
    s->write("HTTP/1.1 " + QByteArray::number(code) + ' ' + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
             + QByteArray::number(body.size()) + "\r\nConnection: close\r\n" + extra + "\r\n" + body);
    s->disconnectFromHost();
}

// ------------------------------------------------------------------ self-test

bool Tor::selfTest(int seconds)
{
    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        fprintf(stderr, "tor self-test: no temporary folder\n");
        return false;
    }
    const QString exe = binary();
    QString version = QStringLiteral("not found");
    if (!exe.isEmpty()) {
        QProcess v;
        v.start(exe, {QStringLiteral("--version")});
        if (v.waitForFinished(10000))
            version = QString::fromUtf8(v.readAllStandardOutput()).split(QLatin1Char('\n')).value(0).trimmed();
    }
    printf("tor self-test: %s (%s)\n", qPrintable(version), qPrintable(exe));
    fflush(stdout);
    if (exe.isEmpty())
        return false;

    Tor tor(nullptr, nullptr, tmp.path() + QStringLiteral("/tor"));
    const QByteArray ping = "huginn tor self-test " + QByteArray::number(QRandomGenerator::global()->generate64(), 16);
    int received = 0;
    tor.onMessage([&](const QByteArray &body) {
        if (body != ping)
            return 400;
        ++received;
        return 200;
    });

    // A fresh loop every time, so a timer left over from one wait can't cut another one short.
    const auto spin = [](int ms) {
        QEventLoop loop;
        QTimer::singleShot(ms, &loop, &QEventLoop::quit);
        loop.exec();
    };
    // One raw request to the local listener; answers the status code, or -1.
    const auto raw = [&](const QByteArray &request) {
        QEventLoop loop;
        QTcpSocket s;
        s.setProxy(QNetworkProxy::NoProxy);
        QByteArray got;
        QObject::connect(&s, &QTcpSocket::readyRead, &loop, [&] { got += s.readAll(); });
        QObject::connect(&s, &QTcpSocket::disconnected, &loop, &QEventLoop::quit);
        QObject::connect(&s, &QTcpSocket::errorOccurred, &loop, &QEventLoop::quit);
        s.connectToHost(QHostAddress::LocalHost, tor.m_server.serverPort());
        s.write(request);
        QTimer::singleShot(5000, &loop, &QEventLoop::quit);
        loop.exec();
        got += s.readAll();
        QObject::disconnect(&s, nullptr, &loop, nullptr);
        s.abort();
        const QList<QByteArray> first = got.split('\n').value(0).split(' ');
        return first.value(0).startsWith("HTTP/1.") ? first.value(1).toInt() : -1;
    };

    QElapsedTimer clock;
    clock.start();
    tor.start();

    // Nothing may go out before Tor is ready.
    int early = 0;
    tor.post(QString(56, QLatin1Char('a')), QString::fromLatin1(MessagePath), ping, [&early](int st) { early = st; });
    spin(50);
    const bool failsClosed = early == -1 && tor.proxy().port() == 0;

    // The local end of the onion service keeps to the Wi-Fi endpoint's rules.
    const QByteArray path = MessagePath;
    const bool listening = tor.m_server.isListening() && tor.m_server.serverAddress() == QHostAddress(QHostAddress::LocalHost);
    const bool rejectsGet = raw("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n") == 405;
    const bool rejectsPath = raw("POST /nope HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n") == 404;
    const bool rejectsBig = raw("POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + QByteArray::number(MaxBody + 1) + "\r\n\r\n") == 413;
    const bool acceptsLocal = raw("POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + QByteArray::number(ping.size()) + "\r\n\r\n" + ping) == 200;
    const bool localOk = listening && rejectsGet && rejectsPath && rejectsBig && acceptsLocal;
    received = 0;

    int shown = -1;
    while (!tor.ready() && tor.state() != QLatin1String("failed") && clock.elapsed() < seconds * 1000LL) {
        if (tor.progress() / 25 != shown / 25 || shown < 0) {
            shown = tor.progress();
            printf("tor self-test: bootstrap %d%%\n", shown);
            fflush(stdout);
        }
        spin(250);
    }
    const bool ready = tor.ready();
    const qint64 bootMs = clock.elapsed();
    const QNetworkProxy p = tor.proxy();
    const bool proxyOk = ready && p.type() == QNetworkProxy::Socks5Proxy && p.hostName() == QLatin1String("127.0.0.1") && p.port() > 0
                         && p.capabilities().testFlag(QNetworkProxy::HostNameLookupCapability);

    // The onion, then a message to it through Tor (the descriptor takes a little while to be found).
    QElapsedTimer trip;
    trip.start();
    while (ready && tor.onion().isEmpty() && trip.elapsed() < seconds * 1000LL)
        spin(250);
    const bool onionOk = validOnion(tor.onion()) && tor.onion().size() == 56;
    // Asking for the descriptor before it is uploaded makes Tor skip those directories for
    // 15 minutes, so wait for the upload (and a little longer, for the other directories).
    while (onionOk && !tor.onionPublished() && trip.elapsed() < seconds * 500LL)
        spin(250);
    if (onionOk)
        spin(5000);
    const bool published = tor.onionPublished();
    int status = -1, tries = 0;
    while (onionOk && status != 200 && trip.elapsed() < seconds * 1000LL) {
        bool answered = false;
        if (tries > 0) {
            // NEWNYM also clears the record of directories already asked, so the next try asks again.
            tor.command("SIGNAL NEWNYM");
            spin(10000);
        }
        ++tries;
        tor.post(tor.onion(), QString::fromLatin1(MessagePath), ping, [&](int st) {
            status = st;
            answered = true;
        });
        while (!answered)
            spin(100);
    }
    const bool roundTrip = status == 200 && received >= 1;

    printf("tor self-test: fails closed before ready %s, local endpoint %s, bootstrap %s (%lld s), SOCKS proxy %s, onion %s "
           "(descriptor uploaded: %s), message to itself through Tor %s (%d tries, %lld s)\n",
           failsClosed ? "ok" : "WRONG", localOk ? "ok" : "WRONG", ready ? "ok" : "WRONG", bootMs / 1000, proxyOk ? "ok" : "WRONG",
           onionOk ? "ok" : "WRONG", published ? "yes" : "no", roundTrip ? "ok" : "WRONG", tries, trip.elapsed() / 1000);
    const bool all = failsClosed && localOk && ready && proxyOk && onionOk && roundTrip;
    if (!all) {
        printf("tor self-test: state %s, progress %d%%, last lines from tor:\n", qPrintable(tor.state()), tor.progress());
        for (const QString &l : std::as_const(tor.m_log))
            printf("  %s\n", qPrintable(l));
    }
    fflush(stdout);
    tor.stop();
    return all;
}
