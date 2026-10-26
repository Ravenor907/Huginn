#include "chat.h"
#include "chatcrypto.h"
#include "localsend.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUuid>

namespace
{
const QString kPath = QStringLiteral("/api/huginn/v1/message");

QString store()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    return dir + QStringLiteral("/chats.json");
}
}

Chat::Chat(LocalSend *net, QObject *parent)
    : QObject(parent)
    , m_net(net)
    , m_settings(QStringLiteral("Huginn"), QStringLiteral("Huginn"))
{
    m_priv = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/priv")).toByteArray());
    m_pub = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/pub")).toByteArray());
    if (m_priv.isEmpty() || m_pub.isEmpty()) {
        const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
        m_priv = kp.privateDer;
        m_pub = kp.publicDer;
        m_settings.setValue(QStringLiteral("chat/priv"), m_priv.toBase64());
        m_settings.setValue(QStringLiteral("chat/pub"), m_pub.toBase64());
    }
    load();
    connect(&m_http, &QNetworkAccessManager::sslErrors, this, [](QNetworkReply *r, const QList<QSslError> &) { r->ignoreSslErrors(); });
    m_net->chatInfo = [this] {
        QJsonObject o{{QStringLiteral("chatKey"), QString::fromLatin1(m_pub.toBase64())}, {QStringLiteral("chatApp"), QStringLiteral("huginn")}};
        if (nearbyOn())
            o.insert(QStringLiteral("chatName"), username());
        return o;
    };
    m_net->chatReceive = [this](const QByteArray &body, const QString &ip) { return receive(body, ip); };
    connect(m_net, &LocalSend::devicesChanged, this, &Chat::changed);
}

QString Chat::username() const
{
    QString n = m_settings.value(QStringLiteral("chat/username")).toString();
    if (n.isEmpty()) {
        // Random until the person picks one, so nobody is named by default.
        n = QStringLiteral("Raven %1").arg(QRandomGenerator::global()->bounded(1000, 10000));
        const_cast<QSettings &>(m_settings).setValue(QStringLiteral("chat/username"), n);
    }
    return n;
}

void Chat::setUsername(const QString &v)
{
    const QString t = v.trimmed().left(24);
    if (t.isEmpty() || t == username())
        return;
    m_settings.setValue(QStringLiteral("chat/username"), t);
    m_net->announce();
    Q_EMIT changed();
}

void Chat::setNearbyOn(bool v)
{
    m_settings.setValue(QStringLiteral("chat/nearbyOn"), v);
    m_net->announce();
    Q_EMIT changed();
}

void Chat::setKeepNearby(bool v)
{
    m_settings.setValue(QStringLiteral("chat/keepNearby"), v);
    if (!v)
        m_nearby.clear();
    save();
    Q_EMIT changed();
}

QByteArray Chat::keyWith(const QString &theirKey) const
{
    return ChatCrypto::sharedKey(m_priv, m_pub, QByteArray::fromBase64(theirKey.toLatin1()));
}

QString Chat::codeWith(const QString &key) const { return ChatCrypto::pairingCode(keyWith(key)); }

bool Chat::isTrusted(const QString &key) const
{
    return m_settings.value(QStringLiteral("chat/trusted")).toMap().contains(key);
}

int Chat::mutedCount() const { return m_settings.value(QStringLiteral("chat/muted")).toStringList().size(); }

QVariantMap Chat::message(const QString &peer, const QString &name, const QString &text, bool mine) const
{
    return {{QStringLiteral("id"), QUuid::createUuid().toString(QUuid::WithoutBraces)}, {QStringLiteral("peer"), peer},
            {QStringLiteral("name"), name}, {QStringLiteral("text"), text}, {QStringLiteral("mine"), mine},
            {QStringLiteral("time"), QDateTime::currentDateTime().toString(QStringLiteral("h:mm AP"))}, {QStringLiteral("failed"), false}};
}

// ------------------------------------------------------------------ receiving

int Chat::receive(const QByteArray &body, const QString &)
{
    const QJsonObject o = QJsonDocument::fromJson(body).object();
    const QString fromKey = o.value(QStringLiteral("fromKey")).toString();
    QString name = o.value(QStringLiteral("fromName")).toString().left(40);
    if (name.isEmpty())
        name = QStringLiteral("Someone");
    if (fromKey.isEmpty() || fromKey == QString::fromLatin1(m_pub.toBase64()))
        return 400;
    // No more than 5 messages in 10 seconds from anyone, so nobody can flood the room.
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    QList<qint64> &q = m_recent[fromKey];
    while (!q.isEmpty() && now - q.first() > 10000)
        q.removeFirst();
    if (q.size() >= 5)
        return 429;
    q << now;
    const QByteArray key = keyWith(fromKey);
    if (key.isEmpty())
        return 400;
    bool ok = false;
    const QByteArray plain = ChatCrypto::open(key, QByteArray::fromBase64(o.value(QStringLiteral("nonce")).toString().toLatin1()),
                                              QByteArray::fromBase64(o.value(QStringLiteral("data")).toString().toLatin1()), &ok);
    if (!ok)
        return 403;
    const QString text = QString::fromUtf8(plain).left(kMax);
    const QString room = o.value(QStringLiteral("room")).toString();
    if (room == QLatin1String("nearby")) {
        if (!nearbyOn())
            return 403;
        if (!m_settings.value(QStringLiteral("chat/muted")).toStringList().contains(fromKey))
            addNearby(message(fromKey, name, text, false));
        return 200;
    }
    if (room == QLatin1String("private")) {
        if (isTrusted(fromKey)) {
            addPrivate(fromKey, message(fromKey, name, text, false));
            Q_EMIT notice(name + QStringLiteral(": ") + text.left(60));
        } else {
            m_held[fromKey] << message(fromKey, name, text, false);
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name}, {QStringLiteral("code"), ChatCrypto::pairingCode(key)}});
                Q_EMIT notice(name + QStringLiteral(" wants to chat privately. Open Chat to check the code."));
                Q_EMIT changed();
            }
        }
        return 200;
    }
    return 400;
}

void Chat::acceptPair(const QString &key)
{
    const QVariantMap r = m_requests.take(key);
    QVariantMap t = m_settings.value(QStringLiteral("chat/trusted")).toMap();
    t.insert(key, r.value(QStringLiteral("name")));
    m_settings.setValue(QStringLiteral("chat/trusted"), t);
    for (const QVariant &m : m_held.take(key))
        addPrivate(key, m.toMap());
    Q_EMIT changed();
}

void Chat::declinePair(const QString &key)
{
    m_requests.remove(key);
    m_held.remove(key);
    Q_EMIT changed();
}

void Chat::mute(const QString &key)
{
    QStringList m = m_settings.value(QStringLiteral("chat/muted")).toStringList();
    if (!m.contains(key))
        m << key;
    m_settings.setValue(QStringLiteral("chat/muted"), m);
    QVariantList kept;
    for (const QVariant &v : std::as_const(m_nearby))
        if (v.toMap().value(QStringLiteral("peer")).toString() != key || v.toMap().value(QStringLiteral("mine")).toBool())
            kept << v;
    m_nearby = kept;
    Q_EMIT changed();
}

void Chat::unmuteAll()
{
    m_settings.remove(QStringLiteral("chat/muted"));
    Q_EMIT changed();
}

// ------------------------------------------------------------------ sending

void Chat::sendNearby(const QString &text)
{
    const QString t = text.trimmed().left(kMax);
    if (t.isEmpty() || !nearbyOn())
        return;
    const QVariantMap mine = message(QString::fromLatin1(m_pub.toBase64()), username(), t, true);
    addNearby(mine);
    const QStringList muted = m_settings.value(QStringLiteral("chat/muted")).toStringList();
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty() && !muted.contains(p.chatKey))
            deliver(p.base, p.chatKey, QStringLiteral("nearby"), t, QString(), QString());
}

void Chat::sendPrivate(const QString &key, const QString &name, const QString &text)
{
    const QString t = text.trimmed().left(kMax);
    if (t.isEmpty())
        return;
    // Only reached after the person confirmed the code on screen.
    if (!isTrusted(key)) {
        QVariantMap tr = m_settings.value(QStringLiteral("chat/trusted")).toMap();
        tr.insert(key, name);
        m_settings.setValue(QStringLiteral("chat/trusted"), tr);
    }
    const QVariantMap mine = message(key, username(), t, true);
    addPrivate(key, mine);
    const QString base = m_net->baseFor(key);
    if (base.isEmpty()) {
        markFailed(key, mine.value(QStringLiteral("id")).toString());
        Q_EMIT notice(name + QStringLiteral(" isn't nearby right now."));
        return;
    }
    deliver(base, key, QStringLiteral("private"), t, key, mine.value(QStringLiteral("id")).toString());
}

void Chat::deliver(const QString &base, const QString &theirKey, const QString &room, const QString &text, const QString &peerKey, const QString &msgId)
{
    QByteArray nonce, data;
    ChatCrypto::seal(keyWith(theirKey), text.toUtf8(), &nonce, &data);
    const QJsonObject body{{QStringLiteral("room"), room}, {QStringLiteral("fromKey"), QString::fromLatin1(m_pub.toBase64())},
                           {QStringLiteral("fromName"), room == QLatin1String("nearby") ? username() : QStringLiteral("Huginn (%1)").arg(username())},
                           {QStringLiteral("nonce"), QString::fromLatin1(nonce.toBase64())}, {QStringLiteral("data"), QString::fromLatin1(data.toBase64())}};
    QNetworkRequest req(QUrl(base + kPath));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(6000);
    QNetworkReply *r = m_http.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(r, &QNetworkReply::finished, this, [this, r, peerKey, msgId] {
        r->deleteLater();
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (!peerKey.isEmpty() && code != 200) {
            markFailed(peerKey, msgId);
            Q_EMIT notice(QStringLiteral("That message didn't arrive. Try again."));
        }
    });
}

// ------------------------------------------------------------------ keeping

void Chat::addNearby(const QVariantMap &m)
{
    m_nearby << m;
    while (m_nearby.size() > 300)
        m_nearby.removeFirst();
    if (keepNearby())
        save();
    Q_EMIT changed();
}

void Chat::addPrivate(const QString &peer, const QVariantMap &m)
{
    QVariantList &l = m_private[peer];
    l << m;
    while (l.size() > 1000)
        l.removeFirst();
    save();
    Q_EMIT changed();
}

void Chat::markFailed(const QString &peer, const QString &id)
{
    QVariantList &l = m_private[peer];
    for (QVariant &v : l) {
        QVariantMap m = v.toMap();
        if (m.value(QStringLiteral("id")).toString() == id) {
            m.insert(QStringLiteral("failed"), true);
            v = m;
        }
    }
    save();
    Q_EMIT changed();
}

QVariantList Chat::conversations() const
{
    const QVariantMap trusted = m_settings.value(QStringLiteral("chat/trusted")).toMap();
    QVariantList out;
    for (auto it = m_private.begin(); it != m_private.end(); ++it) {
        if (it.value().isEmpty())
            continue;
        const QVariantMap last = it.value().last().toMap();
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), trusted.value(it.key(), QStringLiteral("Someone")).toString()},
                           {QStringLiteral("last"), last.value(QStringLiteral("text"))}, {QStringLiteral("time"), last.value(QStringLiteral("time"))}};
    }
    return out;
}

QVariantList Chat::requests() const
{
    QVariantList out;
    for (const QVariantMap &r : m_requests)
        out << r;
    return out;
}

QVariantList Chat::people() const
{
    QVariantList out;
    for (const LocalSend::Peer &p : m_net->chatPeers())
        out << QVariantMap{{QStringLiteral("key"), p.chatKey}, {QStringLiteral("name"), p.alias}, {QStringLiteral("type"), p.type},
                           {QStringLiteral("trusted"), isTrusted(p.chatKey)}, {QStringLiteral("inNearby"), !p.chatName.isEmpty()}};
    return out;
}

int Chat::nearbyCount() const
{
    int n = 0;
    for (const LocalSend::Peer &p : m_net->chatPeers())
        n += p.chatName.isEmpty() ? 0 : 1;
    return n;
}

void Chat::save() const
{
    QJsonObject root;
    QJsonObject priv;
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        priv.insert(it.key(), QJsonArray::fromVariantList(it.value()));
    root.insert(QStringLiteral("private"), priv);
    if (keepNearby())
        root.insert(QStringLiteral("nearby"), QJsonArray::fromVariantList(m_nearby));
    QFile f(store());
    if (f.open(QIODevice::WriteOnly))
        f.write(QJsonDocument(root).toJson(QJsonDocument::Compact));
}

void Chat::load()
{
    QFile f(store());
    if (!f.open(QIODevice::ReadOnly))
        return;
    const QJsonObject root = QJsonDocument::fromJson(f.readAll()).object();
    const QJsonObject priv = root.value(QStringLiteral("private")).toObject();
    for (auto it = priv.begin(); it != priv.end(); ++it)
        m_private.insert(it.key(), it.value().toArray().toVariantList());
    if (keepNearby())
        m_nearby = root.value(QStringLiteral("nearby")).toArray().toVariantList();
}
