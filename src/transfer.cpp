#include "transfer.h"
#include "settings.h"
#include "system.h"

#include "platform/qrcode.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkInterface>
#include <QPainter>
#include <QRandomGenerator>
#include <QTcpSocket>
#include <QTimer>
#include <QUrlQuery>
#include <QStringList>
#include <QVariantMap>

namespace
{
constexpr quint16 kFirstPort = 8420;

QString uniquePath(const QString &dir, const QString &name)
{
    QString clean = QFileInfo(name).fileName();
    clean.remove(QLatin1Char('/'));
    if (clean.isEmpty() || clean == QLatin1String(".") || clean == QLatin1String(".."))
        clean = QStringLiteral("from-phone");
    QString path = QDir(dir).filePath(clean);
    const QFileInfo fi(clean);
    for (int n = 1; QFileInfo::exists(path); ++n) {
        const QString suffix = fi.suffix().isEmpty() ? QString() : QLatin1Char('.') + fi.suffix();
        path = QDir(dir).filePath(fi.completeBaseName() + QStringLiteral(" (%1)").arg(n) + suffix);
    }
    return path;
}
}

TransferServer::TransferServer(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
{
    connect(&m_server, &QTcpServer::newConnection, this, &TransferServer::onConnection);
    newPin();
}

QString TransferServer::saveName() const
{
    return System::displayPath(QUrl::fromLocalFile(m_settings->transferFolder()));
}

QString TransferServer::lanAddress()
{
    for (const QNetworkInterface &iface : QNetworkInterface::allInterfaces()) {
        if (!(iface.flags() & QNetworkInterface::IsUp) || (iface.flags() & QNetworkInterface::IsLoopBack))
            continue;
        for (const QNetworkAddressEntry &e : iface.addressEntries()) {
            const QHostAddress a = e.ip();
            if (a.protocol() == QAbstractSocket::IPv4Protocol && a.isPrivateUse())
                return a.toString();
        }
    }
    return {};
}

void TransferServer::start()
{
    if (running())
        return;
    m_error.clear();
    const QString ip = lanAddress();
    if (ip.isEmpty()) {
        m_error = QStringLiteral("Not connected to a home network");
        Q_EMIT stateChanged();
        return;
    }
    for (quint16 port = kFirstPort; port < kFirstPort + 10; ++port) {
        if (m_server.listen(QHostAddress::AnyIPv4, port)) {
            m_address = QStringLiteral("http://%1:%2").arg(ip).arg(port);
            break;
        }
    }
    if (!running())
        m_error = QStringLiteral("Couldn't open a port for the phone page");
    Q_EMIT stateChanged();
}

void TransferServer::stop()
{
    m_server.close();
    for (auto it = m_conns.begin(); it != m_conns.end(); ++it) {
        if (it->file) {
            it->file->close();
            it->file->remove();
            delete it->file;
        }
        delete it->sending;
        it.key()->abort();
        it.key()->deleteLater();
    }
    m_conns.clear();
    m_failures.clear();
    Q_EMIT stateChanged();
}

void TransferServer::newPin()
{
    m_pin = QStringLiteral("%1").arg(QRandomGenerator::global()->bounded(10000), 4, 10, QLatin1Char('0'));
    m_failures.clear();
    Q_EMIT stateChanged();
}

void TransferServer::share(const QVariantList &urls)
{
    for (const QVariant &v : urls) {
        const QUrl u = v.toUrl().isEmpty() ? QUrl(v.toString()) : v.toUrl();
        const QString p = u.toLocalFile();
        if (QFileInfo(p).isFile() && !m_shared.contains(p))
            m_shared << p;
    }
    Q_EMIT itemsChanged();
}

QVariantList TransferServer::items() const
{
    QVariantList out;
    for (int i = int(m_items.size()) - 1; i >= 0; --i) {
        const Incoming &it = m_items.at(i);
        QString status;
        if (it.failed)
            status = QStringLiteral("Stopped");
        else if (it.done)
            status = QStringLiteral("Done");
        else if (it.received == 0)
            status = QStringLiteral("Waiting");
        else
            status = QStringLiteral("%1 of %2").arg(System::formatSize(it.received), System::formatSize(it.total));
        out << QVariantMap{{QStringLiteral("name"), it.name},
                           {QStringLiteral("status"), status},
                           {QStringLiteral("failed"), it.failed},
                           {QStringLiteral("fraction"), it.total > 0 ? double(it.received) / it.total : (it.done ? 1.0 : 0.0)}};
    }
    return out;
}

void TransferServer::onConnection()
{
    while (QTcpSocket *s = m_server.nextPendingConnection()) {
        m_conns.insert(s, Conn{});
        connect(s, &QTcpSocket::readyRead, this, [this, s] { onData(s); });
        connect(s, &QTcpSocket::disconnected, this, [this, s] {
            auto it = m_conns.find(s);
            if (it != m_conns.end()) {
                if (it->file)
                    finishUpload(nullptr, *it, false);
                delete it->sending;
                m_conns.erase(it);
            }
            s->deleteLater();
        });
    }
}

void TransferServer::onData(QTcpSocket *s)
{
    auto it = m_conns.find(s);
    if (it == m_conns.end())
        return;
    Conn &c = *it;
    if (!c.headDone) {
        c.head += s->readAll();
        const int end = c.head.indexOf("\r\n\r\n");
        if (end < 0) {
            if (c.head.size() > 64 * 1024)
                s->abort();
            return;
        }
        const QByteArray rest = c.head.mid(end + 4);
        const QList<QByteArray> lines = c.head.left(end).split('\n');
        c.headDone = true;
        const QList<QByteArray> first = lines.value(0).trimmed().split(' ');
        c.method = QString::fromLatin1(first.value(0));
        const QUrl target(QString::fromLatin1(first.value(1)));
        c.path = target.path();
        const QUrlQuery q(target);
        for (const auto &kv : q.queryItems(QUrl::FullyDecoded))
            c.query.insert(kv.first, kv.second);
        for (int i = 1; i < lines.size(); ++i) {
            const int colon = lines.at(i).indexOf(':');
            if (colon > 0)
                c.headers.insert(QString::fromLatin1(lines.at(i).left(colon)).trimmed().toLower(),
                                 QString::fromUtf8(lines.at(i).mid(colon + 1)).trimmed());
        }
        c.bodyLeft = c.headers.value(QStringLiteral("content-length")).toLongLong();
        route(s, c);
        if (c.file && !rest.isEmpty()) {
            c.file->write(rest);
            c.bodyLeft -= rest.size();
            m_items[c.item].received += rest.size();
        }
    } else if (c.file) {
        const QByteArray chunk = s->readAll();
        c.file->write(chunk);
        c.bodyLeft -= chunk.size();
        m_items[c.item].received += chunk.size();
    } else {
        s->readAll();
    }
    if (c.file) {
        Q_EMIT itemsChanged();
        if (c.bodyLeft <= 0)
            finishUpload(s, c, true);
    }
}

bool TransferServer::authorized(QTcpSocket *s, const Conn &c)
{
    const QString who = s->peerAddress().toString();
    if (m_failures.value(who) >= 5)
        return false;
    QString given = c.query.value(QStringLiteral("pin"));
    if (given.isEmpty()) {
        const QString cookie = c.headers.value(QStringLiteral("cookie"));
        const int at = cookie.indexOf(QLatin1String("huginn_pin="));
        if (at >= 0)
            given = cookie.mid(at + 11, 4);
    }
    if (given.isEmpty())
        given = c.headers.value(QStringLiteral("x-huginn-pin"));
    if (given == m_pin)
        return true;
    if (!given.isEmpty()) {
        m_failures[who] += 1;
        if (m_failures.value(who) >= 5)
            QTimer::singleShot(60000, this, [this, who] { m_failures.remove(who); });
    }
    return false;
}

void TransferServer::route(QTcpSocket *s, Conn &c)
{
    const bool ok = authorized(s, c);
    if (c.method == QLatin1String("GET") && c.path == QLatin1String("/")) {
        if (!ok) {
            reply(s, 200, "text/html; charset=utf-8", pinPage(c.query.contains(QStringLiteral("pin"))));
            return;
        }
        reply(s, 200, "text/html; charset=utf-8", page(),
              "Set-Cookie: huginn_pin=" + m_pin.toLatin1() + "; Path=/; SameSite=Strict\r\n");
        return;
    }
    if (!ok) {
        reply(s, 403, "text/plain", "Wrong PIN");
        return;
    }
    if (c.method == QLatin1String("POST") && c.path == QLatin1String("/upload")) {
        const QString name = QUrl::fromPercentEncoding(c.headers.value(QStringLiteral("x-file-name")).toUtf8());
        const QString dir = m_settings->transferFolder();
        QDir().mkpath(dir);
        c.file = new QFile(uniquePath(dir, name), this);
        if (!c.file->open(QIODevice::WriteOnly)) {
            delete c.file;
            c.file = nullptr;
            reply(s, 500, "text/plain", "Couldn't save the file");
            return;
        }
        m_items.append({QFileInfo(c.file->fileName()).fileName(), c.bodyLeft, 0, false, false});
        c.item = int(m_items.size()) - 1;
        Q_EMIT itemsChanged();
        if (c.bodyLeft == 0)
            finishUpload(s, c, true);
        return;
    }
    if (c.method == QLatin1String("GET") && c.path == QLatin1String("/shared")) {
        QJsonArray arr;
        for (int i = 0; i < m_shared.size(); ++i) {
            const QFileInfo fi(m_shared.at(i));
            arr.append(QJsonObject{{QStringLiteral("id"), i}, {QStringLiteral("name"), fi.fileName()}, {QStringLiteral("size"), System::formatSize(fi.size())}});
        }
        reply(s, 200, "application/json", QJsonDocument(arr).toJson(QJsonDocument::Compact));
        return;
    }
    if (c.method == QLatin1String("GET") && c.path.startsWith(QLatin1String("/dl/"))) {
        const int id = c.path.mid(4).toInt();
        if (id < 0 || id >= m_shared.size()) {
            reply(s, 404, "text/plain", "Not found");
            return;
        }
        auto *f = new QFile(m_shared.at(id));
        if (!f->open(QIODevice::ReadOnly)) {
            delete f;
            reply(s, 404, "text/plain", "Not found");
            return;
        }
        c.sending = f;
        const QByteArray name = QUrl::toPercentEncoding(QFileInfo(*f).fileName());
        s->write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + QByteArray::number(f->size())
                 + "\r\nContent-Disposition: attachment; filename*=UTF-8''" + name + "\r\nConnection: close\r\n\r\n");
        // Feed the file as the socket drains, so big files never sit in memory.
        auto pump = [s, f] {
            while (s->bytesToWrite() < 4 * 1024 * 1024 && !f->atEnd())
                s->write(f->read(1024 * 1024));
            if (f->atEnd() && s->bytesToWrite() == 0)
                s->disconnectFromHost();
        };
        connect(s, &QTcpSocket::bytesWritten, s, pump);
        pump();
        return;
    }
    reply(s, 404, "text/plain", "Not found");
}

void TransferServer::finishUpload(QTcpSocket *s, Conn &c, bool ok)
{
    if (!c.file)
        return;
    c.file->close();
    if (!ok)
        c.file->remove();
    if (c.item >= 0 && c.item < m_items.size()) {
        m_items[c.item].done = ok;
        m_items[c.item].failed = !ok;
    }
    delete c.file;
    c.file = nullptr;
    Q_EMIT itemsChanged();
    if (s)
        reply(s, 200, "application/json", "{\"ok\":true}");
}

void TransferServer::reply(QTcpSocket *s, int code, const QByteArray &type, const QByteArray &body, const QByteArray &extraHeaders)
{
    const QByteArray reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
    s->write("HTTP/1.1 " + QByteArray::number(code) + ' ' + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: "
             + QByteArray::number(body.size()) + "\r\nCache-Control: no-store\r\nConnection: close\r\n" + extraHeaders + "\r\n" + body);
    s->disconnectFromHost();
}

QByteArray TransferServer::pinPage(bool wrong) const
{
    QByteArray html = R"(<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Huginn</title><style>
body{margin:0;background:#101112;color:#E4E1DA;font-family:system-ui,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
form{background:#17191B;border:1px solid #2C2F33;border-radius:4px;padding:28px;width:min(320px,90vw);text-align:center}
h1{font-size:20px;letter-spacing:4px;margin:0 0 6px}p{color:#9C978D;margin:0 0 18px}
input{width:100%;box-sizing:border-box;font-size:32px;letter-spacing:12px;text-align:center;padding:12px;background:#101112;color:#E4E1DA;border:1px solid #C2A25E;border-radius:3px}
button{margin-top:16px;width:100%;padding:14px;font-size:18px;background:#2A251B;color:#C2A25E;border:1px solid #C2A25E;border-radius:3px}
.bad{color:#D9A79E}</style></head><body><form method="get" action="/">
<h1>HUGINN</h1><p>Enter the PIN shown on the screen</p>
<input name="pin" inputmode="numeric" pattern="[0-9]*" maxlength="4" autofocus autocomplete="off">
<button type="submit">Connect</button>)";
    if (wrong)
        html += R"(<p class="bad" style="margin-top:14px">That PIN didn't match. Check the screen and try again.</p>)";
    html += "</form></body></html>";
    return html;
}

QByteArray TransferServer::page() const
{
    return R"(<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Huginn</title><style>
body{margin:0;background:#101112;color:#E4E1DA;font-family:system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:20px;letter-spacing:4px;margin:8px 0 4px}h2{font-size:14px;letter-spacing:2px;color:#9C978D;margin:28px 0 10px}
p{color:#9C978D;margin:0 0 16px}
label.pick{display:block;text-align:center;padding:36px 16px;border:1px dashed #45484D;border-radius:4px;background:#17191B;font-size:18px}
label.pick b{color:#C2A25E}input[type=file]{display:none}
.row{background:#17191B;border:1px solid #2C2F33;border-radius:4px;padding:12px 14px;margin-top:8px}
.row .top{display:flex;justify-content:space-between;gap:12px}.row .name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar{height:6px;background:#2C2F33;margin-top:8px}.bar i{display:block;height:6px;width:0;background:#C2A25E}
a.dl{display:flex;justify-content:space-between;color:#E4E1DA;text-decoration:none}a.dl span:last-child{color:#C2A25E}
</style></head><body><main>
<h1>HUGINN</h1><p>Files you send go straight to your handheld.</p>
<label class="pick">Tap to choose files<br><b>or drop them here</b><input id="f" type="file" multiple></label>
<div id="up"></div>
<h2>FROM HUGINN</h2><div id="shared"><p>Nothing yet. In Huginn, open Files and choose Pick files to send.</p></div>
</main><script>
const up=document.getElementById('up');
function send(files){let q=[...files];const next=()=>{const f=q.shift();if(!f)return;
 const row=document.createElement('div');row.className='row';
 row.innerHTML='<div class="top"><span class="name"></span><span class="st">Waiting</span></div><div class="bar"><i></i></div>';
 row.querySelector('.name').textContent=f.name;up.prepend(row);
 const x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));
 x.upload.onprogress=e=>{if(e.lengthComputable){row.querySelector('i').style.width=(100*e.loaded/e.total)+'%';row.querySelector('.st').textContent=Math.round(100*e.loaded/e.total)+'%'}};
 x.onload=()=>{row.querySelector('.st').textContent=x.status==200?'Sent':'Failed';row.querySelector('i').style.width='100%';next()};
 x.onerror=()=>{row.querySelector('.st').textContent='Failed';next()};x.send(f)};next()}
document.getElementById('f').onchange=e=>send(e.target.files);
document.body.ondragover=e=>e.preventDefault();document.body.ondrop=e=>{e.preventDefault();send(e.dataTransfer.files)};
function load(){fetch('/shared').then(r=>r.json()).then(a=>{if(!a.length)return;const s=document.getElementById('shared');s.innerHTML='';
 a.forEach(f=>{const r=document.createElement('div');r.className='row';const l=document.createElement('a');l.className='dl';l.href='/dl/'+f.id;
 const n=document.createElement('span');n.textContent=f.name;const z=document.createElement('span');z.textContent=f.size+' ↓';l.append(n,z);r.append(l);s.append(r)})}).catch(()=>{})}
load();setInterval(load,4000);
</script></body></html>)";
}

QImage QrImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    const QString text = QUrl::fromPercentEncoding(id.toUtf8());
    const int side = requestedSize.width() > 0 ? requestedSize.width() : 512;
    QImage out(side, side, QImage::Format_RGB32);
    out.fill(QColor("#E4E1DA"));
    const QImage qr = Platform::qrCode(text, side);
    if (!qr.isNull()) {
        QPainter p(&out);
        p.setRenderHint(QPainter::SmoothPixmapTransform, false);
        p.drawImage(QRect(0, 0, side, side), qr);
    }
    if (size)
        *size = out.size();
    return out;
}
