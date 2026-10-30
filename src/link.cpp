#include "link.h"
#include "chat.h"
#include "chatcrypto.h"
#include "vault.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUuid>
#include <algorithm>
#include <cstdio>

namespace
{
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString jstr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
QString vstr(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
QByteArray compact(const QJsonValue &v)
{
    return v.isArray() ? QJsonDocument(v.toArray()).toJson(QJsonDocument::Compact) : QJsonDocument(v.toObject()).toJson(QJsonDocument::Compact);
}
}

const char Link::kPrefix[] = "MUNINNL1:";

Link::Link(Chat *chat, QObject *parent)
    : QObject(parent)
    , m_chat(chat)
{
    connect(m_chat, &Chat::changed, this, [this] {
        if (!m_chat->ready() && m_loaded) {
            m_loaded = false;
            Q_EMIT changed();
        } else if (m_chat->ready() && !m_loaded) {
            Q_EMIT changed();
        }
    });
}

void Link::ensureLoaded() const
{
    if (m_loaded || !m_chat->ready())
        return;
    m_loaded = true;
    m_companions.clear();
    m_phone = Device();
    m_mirror.clear();
    const QJsonObject o = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("link.json"))).object();
    auto dev = [](const QJsonObject &d) { return Device{jstr(d, "k"), jstr(d, "p"), jstr(d, "n")}; };
    for (const QJsonValue &v : o.value(QStringLiteral("companions")).toArray())
        m_companions << dev(v.toObject());
    if (o.contains(QStringLiteral("phone")))
        m_phone = dev(o.value(QStringLiteral("phone")).toObject());
    const QJsonObject mo = QJsonDocument::fromJson(m_chat->m_vault->read(QStringLiteral("mirror.json"))).object();
    for (auto it = mo.begin(); it != mo.end(); ++it) {
        const QJsonObject c = it.value().toObject();
        Mirror m{jstr(c, "peer"), jstr(c, "n"), {}};
        for (const QJsonValue &v : c.value(QStringLiteral("msgs")).toArray())
            m.msgs << mOf(v.toObject());
        if (!m.peer.isEmpty())
            m_mirror.insert(m.peer, m);
    }
}

void Link::save()
{
    auto devJson = [](const Device &d) { return QJsonObject{{QStringLiteral("k"), d.key}, {QStringLiteral("p"), d.mailbox}, {QStringLiteral("n"), d.name}}; };
    QJsonArray c;
    for (const Device &d : std::as_const(m_companions))
        c.append(devJson(d));
    QJsonObject o{{QStringLiteral("companions"), c}};
    if (!m_phone.key.isEmpty())
        o.insert(QStringLiteral("phone"), devJson(m_phone));
    m_chat->m_vault->write(QStringLiteral("link.json"), compact(o));
    Q_EMIT changed();
}

void Link::saveMirror()
{
    QJsonObject o;
    for (const Mirror &m : std::as_const(m_mirror)) {
        QJsonArray a;
        for (const M &x : m.msgs)
            a.append(QJsonObject{{QStringLiteral("id"), x.id}, {QStringLiteral("x"), x.text}, {QStringLiteral("me"), x.mine}, {QStringLiteral("t"), double(x.time)}});
        o.insert(m.peer, QJsonObject{{QStringLiteral("peer"), m.peer}, {QStringLiteral("n"), m.name}, {QStringLiteral("msgs"), a}});
    }
    m_chat->m_vault->write(QStringLiteral("mirror.json"), compact(o));
    Q_EMIT changed();
}

void Link::sendTo(const Device &d, const QJsonObject &msg)
{
    // Sealed like a private message, on every route at once.
    m_chat->sendEverywhere(d.key, QStringLiteral("link"), msg, d.mailbox.size() == 64 ? d.mailbox : QString());
}

QVariantMap Link::phone() const
{
    ensureLoaded();
    if (m_phone.key.isEmpty())
        return {};
    return {{QStringLiteral("key"), m_phone.key}, {QStringLiteral("name"), m_phone.name}};
}

QVariantList Link::companions() const
{
    ensureLoaded();
    QVariantList out;
    for (const Device &d : std::as_const(m_companions))
        out << QVariantMap{{QStringLiteral("key"), d.key}, {QStringLiteral("name"), d.name}};
    return out;
}

QVariantList Link::mirror() const
{
    ensureLoaded();
    QList<const Mirror *> all;
    for (const Mirror &m : std::as_const(m_mirror))
        all << &m;
    auto last = [](const Mirror *m) { return m->msgs.isEmpty() ? qint64(0) : m->msgs.last().time; };
    std::sort(all.begin(), all.end(), [&](const Mirror *a, const Mirror *b) { return last(a) > last(b); });
    QVariantList out;
    for (const Mirror *m : all)
        out << QVariantMap{{QStringLiteral("peer"), m->peer}, {QStringLiteral("name"), m->name},
                           {QStringLiteral("last"), m->msgs.isEmpty() ? QString() : (m->msgs.last().mine ? QStringLiteral("You: ") : QString()) + m->msgs.last().text.left(80)},
                           {QStringLiteral("time"), double(last(m))}};
    return out;
}

QVariantList Link::messages(const QString &peer) const
{
    ensureLoaded();
    QVariantList out;
    for (const M &x : m_mirror.value(peer).msgs)
        out << QVariantMap{{QStringLiteral("id"), x.id}, {QStringLiteral("text"), x.text}, {QStringLiteral("mine"), x.mine}, {QStringLiteral("time"), double(x.time)}};
    return out;
}

// ------------------------------------------------------------------ pairing

QString Link::newCode()
{
    ensureLoaded();
    if (!m_chat->ready())
        return QString();
    m_token = QUuid::createUuid().toString(QUuid::Id128);
    m_tokenUntil = now() + kTokenMs;
    QString name = m_chat->username();
    const QJsonObject o{{QStringLiteral("k"), m_chat->myKey()}, {QStringLiteral("p"), m_chat->mailbox()}, {QStringLiteral("n"), name.left(40)},
                        {QStringLiteral("i"), m_token}};
    m_code = QString::fromLatin1(kPrefix) + QString::fromLatin1(compact(o).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
    Q_EMIT changed();
    return m_code;
}

QJsonObject Link::readCode(const QString &text)
{
    const QString prefix = QString::fromLatin1(kPrefix);
    const int at = text.indexOf(prefix);
    if (at < 0)
        return {};
    QString body;
    for (const QChar c : text.mid(at + prefix.size())) {
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    const auto dec = QByteArray::fromBase64Encoding(body.toLatin1(), QByteArray::Base64UrlEncoding | QByteArray::AbortOnBase64DecodingErrors);
    if (!dec)
        return {};
    const QJsonObject o = QJsonDocument::fromJson(*dec).object();
    const QString k = jstr(o, "k");
    if (k.size() < 40 || k.size() > 200)
        return {};
    const ChatCrypto::KeyPair t = ChatCrypto::newKeyPair();
    if (ChatCrypto::sharedKey(t.privateDer, t.publicDer, QByteArray::fromBase64(k.toLatin1())).isEmpty())
        return {};
    return {{QStringLiteral("k"), k}, {QStringLiteral("p"), jstr(o, "p")}, {QStringLiteral("n"), jstr(o, "n").left(40)}, {QStringLiteral("i"), jstr(o, "i")}};
}

QString Link::link(const QString &text)
{
    ensureLoaded();
    if (!m_chat->ready())
        return QStringLiteral("Unlock Chat first.");
    if (text.indexOf(QString::fromLatin1(kPrefix)) < 0)
        return QStringLiteral("That isn't a link code. On the other device: Settings › Linked devices › Use as a companion.");
    const QJsonObject o = readCode(text);
    if (o.isEmpty())
        return QStringLiteral("That link code is damaged.");
    const QString k = jstr(o, "k");
    if (k == m_chat->myKey())
        return QStringLiteral("That's this computer's own code.");
    if (k == m_phone.key)
        return QStringLiteral("That device is the phone this one mirrors.");
    QString name = jstr(o, "n").trimmed();
    const Device d{k, jstr(o, "p"), name.isEmpty() ? QStringLiteral("Linked device") : name};
    m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == k; }), m_companions.end());
    m_companions << d;
    save();
    sendTo(d, QJsonObject{{QStringLiteral("t"), QStringLiteral("hello")}, {QStringLiteral("i"), jstr(o, "i")}, {QStringLiteral("n"), m_chat->username()},
                          {QStringLiteral("p"), m_chat->mailbox()}});
    syncAll(&d);
    return QString();
}

void Link::unlink(const QString &key)
{
    ensureLoaded();
    const QJsonObject bye{{QStringLiteral("t"), QStringLiteral("unlink")}};
    for (const Device &d : std::as_const(m_companions))
        if (d.key == key)
            sendTo(d, bye);
    m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == key; }), m_companions.end());
    if (!m_phone.key.isEmpty() && m_phone.key == key) {
        sendTo(m_phone, bye);
        m_phone = Device();
        m_mirror.clear();
        saveMirror();
    }
    save();
}

// ------------------------------------------------------------------ the phone's side

QJsonObject Link::mJson(const QVariantMap &m, int max) const
{
    QString x;
    if (!vstr(m, "unsent").isEmpty())
        x = QStringLiteral("Message deleted");
    else if (m.value(QStringLiteral("voice")).toInt() > 0)
        x = QStringLiteral("Voice message");
    else if (m.value(QStringLiteral("video")).toInt() > 0)
        x = QStringLiteral("Video message");
    else if (!vstr(m, "file").isEmpty())
        x = QStringLiteral("File: ") + vstr(m, "file");
    else if (!vstr(m, "photo").isEmpty())
        x = QStringLiteral("Photo");
    else
        x = vstr(m, "text");
    return {{QStringLiteral("id"), vstr(m, "id")}, {QStringLiteral("x"), x.left(max)}, {QStringLiteral("me"), m.value(QStringLiteral("mine")).toBool()},
            {QStringLiteral("t"), double(m.value(QStringLiteral("time")).toLongLong())}};
}

// Every chat's latest messages to one device (or to every companion).
void Link::syncAll(const Device *only)
{
    const QList<Device> to = only ? QList<Device>{*only} : m_companions;
    if (to.isEmpty())
        return;
    for (auto it = m_chat->m_private.cbegin(); it != m_chat->m_private.cend(); ++it) {
        const QString peer = it.key();
        if (it.value().isEmpty() || !m_chat->isContact(peer) || m_chat->isTrashed(peer))
            continue;
        // Kept well under what an envelope may be: long messages are cut here (the phone has them whole).
        QJsonArray msgs;
        const QVariantList &all = it.value();
        for (qsizetype i = qMax<qsizetype>(0, all.size() - kChatMsgs); i < all.size(); ++i) {
            const QVariantMap m = all.at(i).toMap();
            if (!m.value(QStringLiteral("system")).toBool())
                msgs.append(mJson(m, 600));
        }
        const QJsonObject json{{QStringLiteral("t"), QStringLiteral("chat")}, {QStringLiteral("peer"), peer}, {QStringLiteral("n"), m_chat->nameOf(peer)},
                               {QStringLiteral("msgs"), msgs}};
        for (const Device &d : to)
            sendTo(d, json);
    }
}

void Link::onMessage(const QString &peer, const QVariantMap &m)
{
    ensureLoaded();
    if (m_companions.isEmpty() || !m_chat->isContact(peer) || m.value(QStringLiteral("system")).toBool())
        return;
    const QJsonObject json{{QStringLiteral("t"), QStringLiteral("msg")}, {QStringLiteral("peer"), peer}, {QStringLiteral("n"), m_chat->nameOf(peer)},
                           {QStringLiteral("m"), mJson(m, 2000)}};
    for (const Device &d : std::as_const(m_companions))
        sendTo(d, json);
}

// ------------------------------------------------------------------ room "link", either side

Link::M Link::mOf(const QJsonObject &o)
{
    return M{jstr(o, "id"), jstr(o, "x").left(2000), o.value(QStringLiteral("me")).toBool(), qint64(o.value(QStringLiteral("t")).toDouble())};
}

bool Link::receive(const QString &from, const QJsonObject &p)
{
    ensureLoaded();
    if (!m_chat->ready())
        return false;
    const QString t = jstr(p, "t");
    const bool comp = std::any_of(m_companions.cbegin(), m_companions.cend(), [&](const Device &d) { return d.key == from; });
    const bool ph = !m_phone.key.isEmpty() && m_phone.key == from;
    if (t == QLatin1String("hello") && !comp && !ph) {
        // Pairing: a phone answers my link code with its token, in time and only once.
        if (m_token.isEmpty() || jstr(p, "i") != m_token || now() > m_tokenUntil)
            return false;
        m_token.clear();
        m_code.clear();
        m_tokenUntil = 0;
        const QString n = jstr(p, "n").left(40).trimmed();
        const QString mb = jstr(p, "p");
        m_phone = Device{from, mb.size() == 64 ? mb : QString(), n.isEmpty() ? QStringLiteral("Your phone") : n};
        m_mirror.clear();
        save();
        saveMirror();
        Q_EMIT m_chat->notice(QStringLiteral("Linked. %1's chats show here while it's online.").arg(m_phone.name));
        return true;
    }
    if (t == QLatin1String("unlink") && (comp || ph)) {
        if (comp)
            m_companions.erase(std::remove_if(m_companions.begin(), m_companions.end(), [&](const Device &x) { return x.key == from; }), m_companions.end());
        if (ph) {
            Q_EMIT m_chat->notice(QStringLiteral("%1 unlinked this computer.").arg(m_phone.name));
            m_phone = Device();
            m_mirror.clear();
            saveMirror();
        }
        save();
        return true;
    }
    if (ph && t == QLatin1String("chat")) {
        Mirror m{jstr(p, "peer"), jstr(p, "n").left(40), {}};
        for (const QJsonValue &v : p.value(QStringLiteral("msgs")).toArray())
            m.msgs << mOf(v.toObject());
        std::sort(m.msgs.begin(), m.msgs.end(), [](const M &a, const M &b) { return a.time < b.time; });
        if (!m.peer.isEmpty()) {
            m_mirror.insert(m.peer, m);
            saveMirror();
        }
        return true;
    }
    if (ph && t == QLatin1String("msg")) {
        const QString peer = jstr(p, "peer");
        const QJsonObject mo = p.value(QStringLiteral("m")).toObject();
        if (peer.isEmpty() || mo.isEmpty())
            return true;
        const M x = mOf(mo);
        Mirror &c = m_mirror[peer];
        c.peer = peer;
        const QString n = jstr(p, "n").left(40);
        if (!n.isEmpty())
            c.name = n;
        if (c.name.isEmpty())
            c.name = QStringLiteral("Someone");
        c.msgs.erase(std::remove_if(c.msgs.begin(), c.msgs.end(), [&](const M &y) { return y.id == x.id; }), c.msgs.end());
        c.msgs << x;
        std::sort(c.msgs.begin(), c.msgs.end(), [](const M &a, const M &b) { return a.time < b.time; });
        while (c.msgs.size() > 200)
            c.msgs.removeFirst();
        saveMirror();
        if (!x.mine)
            Q_EMIT m_chat->arrived(c.name, x.text.left(60), QStringLiteral("mirror:") + peer);
        return true;
    }
    if (comp && t == QLatin1String("send")) {
        // The phone sends it as its own message, only to one of its contacts.
        const QString peer = jstr(p, "peer"), text = jstr(p, "text").trimmed();
        if (!text.isEmpty() && m_chat->isContact(peer))
            m_chat->sendPrivate(peer, text.left(Chat::kMax));
        return true;
    }
    if (comp && t == QLatin1String("sync")) {
        for (const Device &d : std::as_const(m_companions))
            if (d.key == from) {
                const Device copy = d;
                syncAll(&copy);
            }
        return true;
    }
    return comp || ph;
}

// ------------------------------------------------------------------ the companion's side

bool Link::send(const QString &peer, const QString &text)
{
    ensureLoaded();
    const QString t = text.trimmed();
    if (m_phone.key.isEmpty() || t.isEmpty())
        return false;
    sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("send")}, {QStringLiteral("peer"), peer}, {QStringLiteral("text"), t.left(Chat::kMax)},
                                {QStringLiteral("id"), QUuid::createUuid().toString(QUuid::WithoutBraces)}});
    return true;
}

void Link::sync()
{
    ensureLoaded();
    if (!m_phone.key.isEmpty())
        sendTo(m_phone, QJsonObject{{QStringLiteral("t"), QStringLiteral("sync")}});
}

// ------------------------------------------------------------------ self-test (tests/i1f1-vectors.json "link")

bool Link::selfTest(const QJsonObject &v)
{
    int bad = 0;
    const QJsonObject l = v.value(QStringLiteral("link")).toObject();
    for (const QJsonValue &x : l.value(QStringLiteral("good")).toArray()) {
        const QJsonObject c = x.toObject();
        if (readCode(jstr(c, "code")) != c.value(QStringLiteral("read")).toObject()) {
            bad++;
            std::fprintf(stderr, "link FAIL: %s\n", qPrintable(jstr(c, "code")));
        }
    }
    for (const QJsonValue &x : l.value(QStringLiteral("bad")).toArray())
        if (!readCode(x.toString()).isEmpty()) {
            bad++;
            std::fprintf(stderr, "link FAIL: should refuse %s\n", qPrintable(x.toString()));
        }
    return bad == 0;
}
