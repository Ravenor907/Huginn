#include "chat.h"
#include "ble.h"
#include "chatcrypto.h"
#include "localsend.h"
#include "nostr.h"
#include "photos.h"
#include "vault.h"

#include <QBuffer>
#include <QClipboard>
#include <QDirIterator>
#include <QFileInfo>
#include <QGuiApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUuid>
#include <memory>

namespace
{
const QString kPath = QStringLiteral("/api/huginn/v1/message");
const QString kPrefix = QStringLiteral("MUNINN1:");
const QString kPage = QStringLiteral("https://ravenor907.github.io/Huginn/add/#");
const int kKeepAfter = 10;
const qint64 kDay = 86400000;
// Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min.
const qint64 kRetry[] = {5000, 10000, 20000, 60000, 300000};

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QVariantMap &m, const char *k) { return m.value(QLatin1String(k)).toString(); }
int rank(const QString &s)
{
    if (s == QLatin1String("sending")) return 1;
    if (s == QLatin1String("sent")) return 2;
    if (s == QLatin1String("delivered")) return 3;
    if (s == QLatin1String("read")) return 4;
    return 0;
}
qint64 retryDelay(int count) { return kRetry[qBound(0, count - 1, 4)]; }
QString b64(const QByteArray &b) { return QString::fromLatin1(b.toBase64()); }
}

Chat::Chat(LocalSend *net, Vault *vault, Nostr *nostr, Ble *ble, QObject *parent)
    : QObject(parent)
    , m_net(net)
    , m_vault(vault)
    , m_nostr(nostr)
    , m_ble(ble)
    , m_settings(QStringLiteral("Huginn"), QStringLiteral("Huginn"))
{
    connect(&m_http, &QNetworkAccessManager::sslErrors, this, [](QNetworkReply *r, const QList<QSslError> &) { r->ignoreSslErrors(); });
    // Nothing is readable until the vault is open: start then.
    connect(m_vault, &Vault::opened, this, &Chat::start);
    if (!m_vault->locked())
        start();
}

void Chat::start()
{
    if (m_ready)
        return;
    // Keys live in the vault. Older versions kept them in plain settings: move them in.
    QJsonObject secrets = QJsonDocument::fromJson(m_vault->read(QStringLiteral("secrets.json"))).object();
    if (secrets.value(QStringLiteral("chatPriv")).toString().isEmpty()) {
        QByteArray priv = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/priv")).toByteArray());
        QByteArray pub = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/pub")).toByteArray());
        if (priv.isEmpty() || pub.isEmpty()) {
            const ChatCrypto::KeyPair kp = ChatCrypto::newKeyPair();
            priv = kp.privateDer;
            pub = kp.publicDer;
        }
        secrets.insert(QStringLiteral("chatPriv"), b64(priv));
        m_vault->write(QStringLiteral("secrets.json"), QJsonDocument(secrets).toJson(QJsonDocument::Compact));
        m_settings.setValue(QStringLiteral("chat/pub"), pub.toBase64());
        m_settings.remove(QStringLiteral("chat/priv"));
    }
    m_priv = QByteArray::fromBase64(secrets.value(QStringLiteral("chatPriv")).toString().toLatin1());
    m_pub = QByteArray::fromBase64(m_settings.value(QStringLiteral("chat/pub")).toByteArray());
    load();
    // People trusted before contacts existed become contacts.
    const QVariantMap trusted = m_settings.value(QStringLiteral("chat/trusted")).toMap();
    for (auto it = trusted.begin(); it != trusted.end(); ++it)
        if (!m_contacts.contains(it.key()))
            m_contacts.insert(it.key(), {{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), it.value()}, {QStringLiteral("verified"), true}});
    if (!trusted.isEmpty()) {
        saveContacts();
        m_settings.remove(QStringLiteral("chat/trusted"));
    }

    m_net->chatInfo = [this] {
        QJsonObject o{{QStringLiteral("chatKey"), myKey()}, {QStringLiteral("chatApp"), QStringLiteral("huginn")},
                      {QStringLiteral("mailbox"), m_nostr->publicKey()}};
        if (nearbyOn())
            o.insert(QStringLiteral("chatName"), username());
        return o;
    };
    m_net->chatReceive = [this](const QByteArray &body, const QString &) { return receive(body, QStringLiteral("wifi")); };
    connect(m_net, &LocalSend::devicesChanged, this, &Chat::changed);
    connect(m_ble, &Ble::message, this, [this](const QString &, const QByteArray &json) { receive(json, QStringLiteral("bluetooth")); });
    connect(m_ble, &Ble::changed, this, &Chat::changed);
    connect(m_nostr, &Nostr::connectedChanged, this, &Chat::changed);
    m_nostr->onEnvelope = [this](const QString &content) {
        const QByteArray body = unwrap(content);
        if (!body.isEmpty())
            receive(body, QStringLiteral("internet"));
        return true;
    };
    m_retry.setInterval(1000);
    connect(&m_retry, &QTimer::timeout, this, &Chat::retryLoop);
    m_retry.start();
    m_prune.setInterval(60000);
    connect(&m_prune, &QTimer::timeout, this, &Chat::prune);
    m_prune.start();
    m_ready = true;
    m_net->announce();
    Q_EMIT changed();
}

// ------------------------------------------------------------------ identity

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
    if (!v) {
        m_nearby.clear();
        m_vault->remove(QStringLiteral("nearby.json"));
    } else {
        save();
    }
    Q_EMIT changed();
}

int Chat::internet() const { return m_nostr->connected(); }

QString Chat::code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite, qint64 expires)
{
    QJsonObject o{{QStringLiteral("n"), name}, {QStringLiteral("k"), chatKey}, {QStringLiteral("p"), mailbox}};
    if (!invite.isEmpty()) {
        o.insert(QStringLiteral("i"), invite);
        o.insert(QStringLiteral("x"), double(expires / 1000));
    }
    return kPrefix + QString::fromLatin1(QJsonDocument(o).toJson(QJsonDocument::Compact).toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

QString Chat::mailbox() const { return m_nostr->publicKey(); }

QString Chat::newLink()
{
    if (!m_ready)
        return QString();
    const QString token = QUuid::createUuid().toString(QUuid::Id128).left(16);
    m_linkExpires = now() + 3600000;
    m_invites.insert(token, m_linkExpires);
    for (auto it = m_invites.begin(); it != m_invites.end();)
        it = it.value() < now() ? m_invites.erase(it) : std::next(it);
    saveInvites();
    m_link = kPage + code(username(), myKey(), m_nostr->publicKey(), token, m_linkExpires);
    Q_EMIT changed();
    return m_link;
}

void Chat::saveInvites()
{
    QJsonObject o;
    for (auto it = m_invites.begin(); it != m_invites.end(); ++it)
        o.insert(it.key(), double(it.value()));
    m_vault->write(QStringLiteral("invites.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool Chat::inviteOk(const QString &token) const { return !token.isEmpty() && m_invites.value(token, 0) > now(); }

// Over the internet the whole message is sealed to their chat key with a one-off key, so relays
// see only which inbox it's for: not who sent it, not their name. The same as Muninn's wrap().
QString Chat::wrap(const QString &peerKey, const QByteArray &body) const
{
    const ChatCrypto::KeyPair eph = ChatCrypto::newKeyPair();
    const QByteArray key = ChatCrypto::sharedKey(eph.privateDer, eph.publicDer, QByteArray::fromBase64(peerKey.toLatin1()));
    QByteArray nonce, data;
    ChatCrypto::seal(key, body, &nonce, &data);
    const QJsonObject o{{QStringLiteral("e"), b64(eph.publicDer)}, {QStringLiteral("n"), b64(nonce)}, {QStringLiteral("d"), b64(data)}};
    return b64(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

QByteArray Chat::unwrap(const QString &content) const
{
    const QByteArray raw = QByteArray::fromBase64(content.toLatin1());
    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    if (!o.contains(QStringLiteral("e")))
        return raw;   // from a version before sealing
    bool ok = false;
    const QByteArray plain = ChatCrypto::open(keyWith(o.value(QStringLiteral("e")).toString()), QByteArray::fromBase64(o.value(QStringLiteral("n")).toString().toLatin1()),
                                              QByteArray::fromBase64(o.value(QStringLiteral("d")).toString().toLatin1()), &ok);
    return ok ? plain : QByteArray();
}

QVariantMap Chat::parseCode(const QString &text) const
{
    const int i = text.indexOf(kPrefix);
    if (i < 0)
        return {};
    QString body;
    for (const QChar c : text.mid(i + kPrefix.size())) {
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-') || c == QLatin1Char('_')))
            break;
        body += c;
    }
    const QJsonObject o = QJsonDocument::fromJson(QByteArray::fromBase64(body.toLatin1(), QByteArray::Base64UrlEncoding)).object();
    const QString key = o.value(QStringLiteral("k")).toString();
    if (key.isEmpty() || keyWith(key).isEmpty())
        return {};   // must be a real key
    QString name = o.value(QStringLiteral("n")).toString().left(40).trimmed();
    if (name.isEmpty())
        name = QStringLiteral("Someone");
    const QString mailbox = o.value(QStringLiteral("p")).toString();
    const qint64 expires = qint64(o.value(QStringLiteral("x")).toDouble()) * 1000;
    return {{QStringLiteral("key"), key}, {QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox.size() == 64 ? mailbox : QString()},
            {QStringLiteral("mine"), key == myKey()}, {QStringLiteral("invite"), o.value(QStringLiteral("i")).toString()},
            {QStringLiteral("expired"), expires > 0 && expires < now()}};
}

void Chat::addContact(const QString &text)
{
    const QVariantMap c = parseCode(text);
    if (c.isEmpty() || c.value(QStringLiteral("mine")).toBool() || c.value(QStringLiteral("expired")).toBool())
        return;
    const QString key = str(c, "key");
    // Whoever gave you their code is who they say: the code itself carries their key.
    m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), c.value(QStringLiteral("name"))},
                            {QStringLiteral("mailbox"), c.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), true},
                            {QStringLiteral("invite"), c.value(QStringLiteral("invite"))}});
    m_met.remove(key);
    saveContacts();
    saveMet();
    Q_EMIT changed();
}

QByteArray Chat::keyWith(const QString &theirKey) const
{
    return ChatCrypto::sharedKey(m_priv, m_pub, QByteArray::fromBase64(theirKey.toLatin1()));
}

QString Chat::codeWith(const QString &key) const { return ChatCrypto::pairingCode(keyWith(key)); }

QString Chat::nameOf(const QString &key) const
{
    if (m_contacts.contains(key))
        return str(m_contacts.value(key), "name");
    if (m_met.contains(key))
        return str(m_met.value(key), "name");
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            return str(p.toMap(), "name");
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (p.chatKey == key)
            return p.chatName.isEmpty() ? p.alias : p.chatName;
    return QStringLiteral("Someone");
}

QString Chat::mailboxOf(const QString &key) const
{
    const QString c = str(m_contacts.value(key), "mailbox");
    return c.isEmpty() ? str(m_met.value(key), "mailbox") : c;
}

QString Chat::route(const QString &key) const
{
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            return QStringLiteral("bluetooth");
    if (!m_net->baseFor(key).isEmpty())
        return QStringLiteral("wifi");
    if (!str(m_contacts.value(key), "mailbox").isEmpty())
        return QStringLiteral("internet");
    return QString();
}

bool Chat::isVerified(const QString &key) const
{
    return m_contacts.value(key).value(QStringLiteral("verified")).toBool() || m_met.value(key).value(QStringLiteral("verified")).toBool();
}

void Chat::verify(const QString &key)
{
    if (m_contacts.contains(key)) {
        m_contacts[key].insert(QStringLiteral("verified"), true);
        saveContacts();
    } else {
        QVariantMap m = m_met.value(key, {{QStringLiteral("name"), nameOf(key)}});
        m.insert(QStringLiteral("verified"), true);
        putMet(key, m);
    }
    Q_EMIT changed();
}

void Chat::meet(const QString &key, const QString &name)
{
    if (isContact(key) || m_met.contains(key))
        return;
    QString mailbox;
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            mailbox = str(p.toMap(), "mailbox");
    putMet(key, {{QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox}});
}

bool Chat::shouldAskKeep(const QString &key) const
{
    if (isContact(key) || !m_met.contains(key))
        return false;
    const QVariantMap m = m_met.value(key);
    if (m.value(QStringLiteral("dismissed")).toBool() || m.value(QStringLiteral("iSaid")).toBool())
        return false;
    return m.value(QStringLiteral("theySaid")).toBool() || m_private.value(key).size() >= kKeepAfter || route(key).isEmpty();
}

bool Chat::saidKeep(const QString &key) const { return m_met.value(key).value(QStringLiteral("iSaid")).toBool(); }
bool Chat::theySaidKeep(const QString &key) const { return m_met.value(key).value(QStringLiteral("theySaid")).toBool(); }

void Chat::keepInTouch(const QString &key)
{
    if (!m_met.contains(key))
        return;
    QVariantMap m = m_met.value(key);
    m.insert(QStringLiteral("iSaid"), true);
    m.insert(QStringLiteral("dismissed"), false);
    putMet(key, m);
    if (deliver(key, envelope(QStringLiteral("keep"), key, QByteArrayLiteral("yes"), QUuid::createUuid().toString(QUuid::WithoutBraces)), str(m, "mailbox")).isEmpty())
        m_unsentKeeps.insert(key);
    if (m.value(QStringLiteral("theySaid")).toBool())
        becomeContacts(key);
    else
        Q_EMIT notice(QStringLiteral("Asked %1. Once they say yes too, you can message from anywhere.").arg(str(m, "name")));
    Q_EMIT changed();
}

void Chat::notNow(const QString &key)
{
    if (!m_met.contains(key))
        return;
    QVariantMap m = m_met.value(key);
    m.insert(QStringLiteral("dismissed"), true);
    putMet(key, m);
    Q_EMIT changed();
}

void Chat::becomeContacts(const QString &key)
{
    const QVariantMap m = m_met.take(key);
    m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), m.value(QStringLiteral("name"))},
                            {QStringLiteral("mailbox"), m.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), m.value(QStringLiteral("verified"))}});
    saveContacts();
    saveMet();
    Q_EMIT notice(QStringLiteral("You and %1 are in touch. Message each other from anywhere.").arg(str(m, "name")));
    Q_EMIT changed();
}

void Chat::putMet(const QString &key, const QVariantMap &m)
{
    m_met.insert(key, m);
    saveMet();
}

void Chat::acceptRequest(const QString &key)
{
    const QVariantMap r = m_requests.take(key);
    m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), r.value(QStringLiteral("name"))},
                            {QStringLiteral("mailbox"), r.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), false}});
    saveContacts();
    const QVariantMap held = m_held.take(key);
    QStringList ids;
    for (const QVariant &v : held) {
        addPrivate(key, v.toMap());
        ids << str(v.toMap(), "id");
    }
    receipt(key, ids, QStringLiteral("delivered"), str(r, "mailbox"));
    Q_EMIT changed();
}

void Chat::declineRequest(const QString &key)
{
    m_requests.remove(key);
    m_held.remove(key);
    Q_EMIT changed();
}

bool Chat::isBlocked(const QString &key) const { return m_settings.value(QStringLiteral("chat/muted")).toStringList().contains(key); }

void Chat::block(const QString &key, bool on)
{
    QStringList m = m_settings.value(QStringLiteral("chat/muted")).toStringList();
    m.removeAll(key);
    if (on)
        m << key;
    m_settings.setValue(QStringLiteral("chat/muted"), m);
    if (on) {
        m_requests.remove(key);
        m_held.remove(key);
        QVariantList kept;
        for (const QVariant &v : std::as_const(m_nearby))
            if (str(v.toMap(), "peer") != key || v.toMap().value(QStringLiteral("mine")).toBool())
                kept << v;
        m_nearby = kept;
    }
    Q_EMIT changed();
}

void Chat::removeContact(const QString &key)
{
    m_contacts.remove(key);
    m_met.remove(key);
    saveContacts();
    saveMet();
    Q_EMIT changed();
}

// ------------------------------------------------------------------ receiving

int Chat::receive(const QByteArray &body, const QString &via)
{
    if (!m_ready)
        return 503;
    const QJsonObject o = QJsonDocument::fromJson(body).object();
    const QString fromKey = o.value(QStringLiteral("fromKey")).toString();
    QString name = o.value(QStringLiteral("fromName")).toString().left(40);
    if (name.isEmpty())
        name = QStringLiteral("Someone");
    if (fromKey.isEmpty() || fromKey == myKey())
        return 400;
    if (isBlocked(fromKey))
        return 200;   // blocked: dropped quietly
    const QString room = o.value(QStringLiteral("room")).toString();
    if (room == QLatin1String("photo"))
        return receivePiece(o, fromKey, name, via);
    QString mailbox = o.value(QStringLiteral("mailbox")).toString();
    if (mailbox.size() != 64)
        mailbox.clear();
    const QString sentId = o.value(QStringLiteral("id")).toString();
    // A copy that's already here (another route, or a resend): confirm again, don't count it.
    if (room == QLatin1String("private") && !sentId.isEmpty() && known(fromKey, sentId)) {
        if (!m_held.value(fromKey).contains(sentId))
            receipt(fromKey, {sentId}, QStringLiteral("delivered"), mailbox);
        return 200;
    }
    if (room == QLatin1String("nearby") && !sentId.isEmpty()) {
        if (m_deleted.contains(sentId))
            return 200;
        for (const QVariant &v : std::as_const(m_nearby))
            if (str(v.toMap(), "id") == sentId)
                return 200;
    }
    // No more than 5 messages in 10 seconds from anyone, so nobody can flood a room.
    if (room != QLatin1String("receipt")) {
        QList<qint64> &q = m_recent[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= 5)
            return 429;
        q << now();
    }
    const QByteArray key = keyWith(fromKey);
    if (key.isEmpty())
        return 400;
    bool ok = false;
    const QByteArray plain = ChatCrypto::open(key, QByteArray::fromBase64(o.value(QStringLiteral("nonce")).toString().toLatin1()),
                                              QByteArray::fromBase64(o.value(QStringLiteral("data")).toString().toLatin1()), &ok);
    if (!ok)
        return 403;
    const QString text = room == QLatin1String("receipt") ? QString::fromUtf8(plain) : QString::fromUtf8(plain).left(kMax);
    const QString msgId = sentId.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces) : sentId;

    if (room == QLatin1String("nearby")) {
        if (!nearbyOn() || via == QLatin1String("internet"))
            return 403;
        addNearby(message(msgId, fromKey, name, text, false, via));
        return 200;
    }
    if (room == QLatin1String("private")) {
        if (isContact(fromKey)) {
            if (!mailbox.isEmpty() && str(m_contacts.value(fromKey), "mailbox") != mailbox) {
                m_contacts[fromKey].insert(QStringLiteral("mailbox"), mailbox);
                saveContacts();
            }
            if (addPrivate(fromKey, message(msgId, fromKey, name, text, false, via))) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(str(m_contacts.value(fromKey), "name"), text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (via != QLatin1String("internet") || m_met.contains(fromKey)) {
            // Close by or on the same Wi-Fi (or someone met that way): no asking first.
            QVariantMap m = m_met.value(fromKey);
            m.insert(QStringLiteral("name"), name);
            if (!mailbox.isEmpty())
                m.insert(QStringLiteral("mailbox"), mailbox);
            putMet(fromKey, m);
            if (addPrivate(fromKey, message(msgId, fromKey, name, text, false, via))) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(name, text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (!inviteOk(o.value(QStringLiteral("invite")).toString())) {
            return 403;   // from far away with no valid invite (an old or leaked code): dropped quietly
        } else {
            // Someone with this computer's code, from far away: ask first.
            m_held[fromKey].insert(msgId, message(msgId, fromKey, name, text, false, via));
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name},
                                            {QStringLiteral("code"), ChatCrypto::pairingCode(key)}, {QStringLiteral("mailbox"), mailbox}});
                Q_EMIT notice(name + QStringLiteral(" wants to chat"));
                Q_EMIT arrived(name, QStringLiteral("Wants to chat with you"), fromKey);
            }
        }
        Q_EMIT changed();
        return 200;
    }
    if (room == QLatin1String("receipt")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey))
            return 403;
        const QJsonObject r = QJsonDocument::fromJson(text.toUtf8()).object();
        const QString st = r.value(QStringLiteral("s")).toString();
        if (st != QLatin1String("delivered") && st != QLatin1String("read") && st != QLatin1String("unsent"))
            return 400;
        QSet<QString> ids;
        const QJsonArray a = r.value(QStringLiteral("ids")).toArray();
        for (int i = 0; i < qMin(int(a.size()), 200); ++i)
            ids.insert(a.at(i).toString());
        QVariantList &l = m_private[fromKey];
        bool any = false;
        for (QVariant &v : l) {
            QVariantMap m = v.toMap();
            if (!m.value(QStringLiteral("mine")).toBool() || !ids.contains(str(m, "id")))
                continue;
            if (st == QLatin1String("unsent")) {
                if (str(m, "state") == QLatin1String("unsending")) {
                    m.insert(QStringLiteral("state"), QStringLiteral("unsent"));
                    m_tries.remove(str(m, "id"));
                }
            } else if (str(m, "unsent").isEmpty() && rank(st) > rank(str(m, "state"))) {
                m.insert(QStringLiteral("state"), st);
                m.insert(QStringLiteral("failed"), false);
            }
            v = m;
            any = true;
        }
        if (any) {
            save();
            Q_EMIT changed();
        }
        return 200;
    }
    if (room == QLatin1String("unsend")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey))
            return 403;
        const QString gone = text.trimmed();
        bool found = false;
        QVariantList &l = m_private[fromKey];
        for (QVariant &v : l) {
            QVariantMap m = v.toMap();
            if (str(m, "id") != gone || m.value(QStringLiteral("mine")).toBool())
                continue;
            found = true;
            if (!str(m, "photo").isEmpty())
                m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
            m.insert(QStringLiteral("text"), QString());
            m.insert(QStringLiteral("photo"), QString());
            m.insert(QStringLiteral("voice"), 0);
            m.insert(QStringLiteral("unsent"), name);
            v = m;
        }
        if (!found) {
            // Not here yet (still on its way): make sure it never shows.
            forget({gone});
            if (m_held.contains(fromKey))
                m_held[fromKey].remove(gone);
        }
        save();
        receipt(fromKey, {gone}, QStringLiteral("unsent"), mailbox);
        Q_EMIT changed();
        return 200;
    }
    if (room == QLatin1String("keep")) {
        if (isContact(fromKey))
            return 200;
        if (!m_met.contains(fromKey) && via == QLatin1String("internet"))
            return 403;
        QVariantMap m = m_met.value(fromKey, {{QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox}});
        if (text != QLatin1String("yes") || m.value(QStringLiteral("theySaid")).toBool())
            return 200;
        m.insert(QStringLiteral("theySaid"), true);
        if (!mailbox.isEmpty())
            m.insert(QStringLiteral("mailbox"), mailbox);
        putMet(fromKey, m);
        if (m.value(QStringLiteral("iSaid")).toBool())
            becomeContacts(fromKey);
        else {
            Q_EMIT notice(name + QStringLiteral(" wants to keep in touch"));
            Q_EMIT arrived(name, QStringLiteral("Wants to keep in touch"), fromKey);
        }
        Q_EMIT changed();
        return 200;
    }
    return 400;
}

// ------------------------------------------------------------------ photos and voice
// Shrunk on the sending side, cut into 16 KB pieces, each sealed and sent like a message
// (room "photo"); the other side puts them back together and answers "delivered".

QString Chat::sendBlob(const QString &key, const QByteArray &bytes, int voiceMs)
{
    const QString name = nameOf(key);
    if (!isContact(key))
        meet(key, name);
    const QString r = route(key);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_vault->write(QStringLiteral("photos/") + id, bytes);
    QVariantMap mine = message(id, key, username(), QString(), true, r);
    mine.insert(QStringLiteral("state"), QStringLiteral("sending"));
    mine.insert(QStringLiteral("photo"), id);
    mine.insert(QStringLiteral("voice"), voiceMs);
    m_tries.insert(id, Tries{0, now() + kRetry[0]});
    addPrivate(key, mine);
    if (r.isEmpty() && backupMailbox(mine).isEmpty()) {
        update(key, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), true); });
        Q_EMIT notice(name + QStringLiteral(" is out of range. Say yes to Keep in touch to send from anywhere."));
        return id;
    }
    attempt(key, id);
    return id;
}

bool Chat::sendPhoto(const QString &key, const QString &path, bool hd)
{
    const QByteArray jpeg = Photos::prepare(path, hd);
    if (jpeg.isEmpty()) {
        Q_EMIT notice(QStringLiteral("That picture couldn't be opened."));
        return false;
    }
    sendBlob(key, jpeg, 0);
    return true;
}

void Chat::sendVoice(const QString &key, const QByteArray &aac, int ms)
{
    if (!Photos::isAdts(aac))
        return;
    sendBlob(key, aac, qBound(1, ms, Photos::kMaxVoiceMs));
}

QByteArray Chat::blob(const QString &id) const { return id.isEmpty() ? QByteArray() : m_vault->read(QStringLiteral("photos/") + id); }

QString Chat::photoUrl(const QString &id)
{
    // QML shows pictures from a URL: a data URL keeps it in memory, never on disk unencrypted.
    const QByteArray b = blob(id);
    return b.isEmpty() ? QString() : QStringLiteral("data:image/jpeg;base64,") + b64(b);
}

bool Chat::savePhoto(const QString &id)
{
    const QByteArray b = blob(id);
    if (b.isEmpty())
        return false;
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation) + QStringLiteral("/Huginn");
    QDir().mkpath(dir);
    QFile f(dir + QStringLiteral("/Huginn-%1.jpg").arg(now()));
    return f.open(QIODevice::WriteOnly) && f.write(b) == b.size();
}

QVariantList Chat::recentPictures() const
{
    // Steam's screenshots (every account) and the Pictures folder, the 40 newest.
    const QString home = QDir::homePath();
    QStringList roots{home + QStringLiteral("/.local/share/Steam/userdata"), home + QStringLiteral("/.steam/steam/userdata"),
                      QStandardPaths::writableLocation(QStandardPaths::PicturesLocation)};
    QList<QFileInfo> found;
    QSet<QString> seen;
    for (const QString &root : roots) {
        if (!QDir(root).exists())
            continue;
        QDirIterator it(root, {QStringLiteral("*.jpg"), QStringLiteral("*.jpeg"), QStringLiteral("*.png"), QStringLiteral("*.webp")}, QDir::Files,
                        QDirIterator::Subdirectories);
        int n = 0;
        while (it.hasNext() && n < 4000) {
            const QFileInfo f(it.next());
            ++n;
            if (f.path().contains(QLatin1String("/thumbnails")) || seen.contains(f.canonicalFilePath()))
                continue;
            seen.insert(f.canonicalFilePath());
            found << f;
        }
    }
    std::sort(found.begin(), found.end(), [](const QFileInfo &a, const QFileInfo &b) { return a.lastModified() > b.lastModified(); });
    QVariantList out;
    for (const QFileInfo &f : found.mid(0, 40))
        out << QVariantMap{{QStringLiteral("name"), f.fileName()}, {QStringLiteral("detail"), f.lastModified().toString(QStringLiteral("MMM d, h:mm AP"))},
                           {QStringLiteral("url"), f.absoluteFilePath()}};
    return out;
}

void Chat::copyLink() const
{
    if (auto *c = QGuiApplication::clipboard())
        c->setText(myLink());
}

QList<QJsonObject> Chat::pieces(const QVariantMap &m) const
{
    const QByteArray bytes = blob(str(m, "photo"));
    if (bytes.isEmpty())
        return {};
    const QByteArray key = keyWith(str(m, "peer"));
    const QList<QByteArray> parts = Photos::split(bytes);
    QList<QJsonObject> out;
    const int voice = m.value(QStringLiteral("voice")).toInt();
    for (int i = 0; i < parts.size(); ++i) {
        QByteArray nonce, data;
        ChatCrypto::seal(key, parts.at(i), &nonce, &data);
        QJsonObject o{{QStringLiteral("room"), QStringLiteral("photo")}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                      {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), str(m, "id")}, {QStringLiteral("i"), i},
                      {QStringLiteral("n"), int(parts.size())}, {QStringLiteral("nonce"), b64(nonce)}, {QStringLiteral("data"), b64(data)}};
        if (voice > 0) {
            o.insert(QStringLiteral("kind"), QStringLiteral("voice"));
            o.insert(QStringLiteral("ms"), voice);
        }
        out << o;
    }
    return out;
}

int Chat::receivePiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &via)
{
    const bool contact = isContact(fromKey);
    if (!contact && via == QLatin1String("internet") && !m_met.contains(fromKey))
        return 403;
    const QString id = o.value(QStringLiteral("id")).toString();
    const int n = o.value(QStringLiteral("n")).toInt(), i = o.value(QStringLiteral("i")).toInt(-1);
    if (id.isEmpty() || n < 1 || n > Photos::kMaxPieces || i < 0 || i >= n)
        return 400;
    QString mailbox = o.value(QStringLiteral("mailbox")).toString();
    if (mailbox.size() != 64)
        mailbox.clear();
    // Already have it (a copy from another route, or a resend): just confirm again.
    if (known(fromKey, id)) {
        receipt(fromKey, {id}, QStringLiteral("delivered"), mailbox);
        return 200;
    }
    QList<qint64> &q = m_pieceTimes[fromKey];
    while (!q.isEmpty() && now() - q.first() > 60000)
        q.removeFirst();
    if (q.size() >= 1000)
        return 429;   // about 15 MB a minute at most
    q << now();
    if (m_incoming.size() > 20 && !m_incoming.contains(id))
        return 429;
    bool ok = false;
    const QByteArray part = ChatCrypto::open(keyWith(fromKey), QByteArray::fromBase64(o.value(QStringLiteral("nonce")).toString().toLatin1()),
                                             QByteArray::fromBase64(o.value(QStringLiteral("data")).toString().toLatin1()), &ok);
    if (!ok)
        return 403;
    Incoming &inc = m_incoming[id];
    if (inc.parts.isEmpty()) {
        inc.from = fromKey;
        inc.started = now();
        for (int k = 0; k < n; ++k)
            inc.parts << QByteArray();
    }
    if (inc.from != fromKey || inc.parts.size() != n)
        return 400;
    if (inc.parts.at(i).isEmpty()) {
        inc.parts[i] = part;
        inc.have++;
    }
    if (inc.have < n)
        return 200;
    const QList<QByteArray> parts = m_incoming.take(id).parts;
    const int voiceMs = o.value(QStringLiteral("kind")).toString() == QLatin1String("voice") ? qBound(1, o.value(QStringLiteral("ms")).toInt(), Photos::kMaxVoiceMs) : 0;
    const QByteArray joined = voiceMs > 0 ? Photos::joinVoice(parts) : Photos::join(parts);   // join() also strips any metadata
    if (joined.isEmpty())
        return 400;
    m_vault->write(QStringLiteral("photos/") + id, joined);
    if (!contact) {
        QVariantMap m = m_met.value(fromKey);
        m.insert(QStringLiteral("name"), name);
        if (!mailbox.isEmpty())
            m.insert(QStringLiteral("mailbox"), mailbox);
        putMet(fromKey, m);
    }
    QVariantMap msg = message(id, fromKey, name, QString(), false, via);
    msg.insert(QStringLiteral("photo"), id);
    msg.insert(QStringLiteral("voice"), voiceMs);
    if (addPrivate(fromKey, msg)) {
        m_unread.insert(fromKey);
        Q_EMIT arrived(contact ? str(m_contacts.value(fromKey), "name") : name, voiceMs > 0 ? QStringLiteral("Voice message") : QStringLiteral("Photo"), fromKey);
    }
    receipt(fromKey, {id}, QStringLiteral("delivered"), mailbox);
    return 200;
}

// ------------------------------------------------------------------ sending

QVariantMap Chat::message(const QString &id, const QString &peer, const QString &name, const QString &text, bool mine, const QString &via) const
{
    return {{QStringLiteral("id"), id}, {QStringLiteral("peer"), peer}, {QStringLiteral("name"), name}, {QStringLiteral("text"), text},
            {QStringLiteral("mine"), mine}, {QStringLiteral("time"), now()}, {QStringLiteral("failed"), false}, {QStringLiteral("via"), via},
            {QStringLiteral("state"), QString()}, {QStringLiteral("photo"), QString()}, {QStringLiteral("unsent"), QString()}, {QStringLiteral("voice"), 0}};
}

QJsonObject Chat::envelope(const QString &room, const QString &theirKey, const QByteArray &plain, const QString &msgId) const
{
    QByteArray nonce, data;
    ChatCrypto::seal(keyWith(theirKey), plain, &nonce, &data);
    QJsonObject o{{QStringLiteral("room"), room}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                  {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), msgId},
                  {QStringLiteral("nonce"), b64(nonce)}, {QStringLiteral("data"), b64(data)}};
    // The invite from their code, until they accept: proves they handed it out within the hour.
    const QString invite = str(m_contacts.value(theirKey), "invite");
    if (!invite.isEmpty())
        o.insert(QStringLiteral("invite"), invite);
    return o;
}

void Chat::post(const QString &base, const QJsonObject &body, std::function<void(int)> done)
{
    // The same certificate pinning as file transfers: only the device that announced itself.
    QNetworkRequest req = m_net->peerRequest(QUrl(base + kPath));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(6000);
    QNetworkReply *r = m_http.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    m_net->pinReply(r);
    connect(r, &QNetworkReply::finished, this, [r, done] {
        r->deleteLater();
        if (done)
            done(r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt());
    });
}

QString Chat::deliver(const QString &key, const QJsonObject &body, const QString &mailbox)
{
    const QByteArray json = QJsonDocument(body).toJson(QJsonDocument::Compact);
    if (m_ble->send(key, json))
        return QStringLiteral("bluetooth");
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty()) {
        post(base, body);
        return QStringLiteral("wifi");
    }
    if (!mailbox.isEmpty()) {
        m_nostr->publish(mailbox, wrap(key, json));
        return QStringLiteral("internet");
    }
    return QString();
}

QString Chat::backupMailbox(const QVariantMap &m) const
{
    // Contacts always; someone met close by only for messages sent while they were in range.
    const QString key = str(m, "peer");
    const QString c = str(m_contacts.value(key), "mailbox");
    if (!c.isEmpty())
        return c;
    const QString via = str(m, "via");
    return via == QLatin1String("bluetooth") || via == QLatin1String("wifi") ? str(m_met.value(key), "mailbox") : QString();
}

void Chat::sendNearby(const QString &text)
{
    const QString t = text.trimmed().left(kMax);
    if (t.isEmpty() || !nearbyOn() || !m_ready)
        return;
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    addNearby(message(id, myKey(), username(), t, true, QString()));
    const QStringList muted = m_settings.value(QStringLiteral("chat/muted")).toStringList();
    QSet<QString> done;
    for (const QVariant &v : m_ble->peers()) {
        const QString k = str(v.toMap(), "chatKey");
        if (!k.isEmpty() && !muted.contains(k) && !done.contains(k)) {
            done.insert(k);
            m_ble->send(k, QJsonDocument(envelope(QStringLiteral("nearby"), k, t.toUtf8(), id)).toJson(QJsonDocument::Compact));
        }
    }
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty() && !muted.contains(p.chatKey) && !done.contains(p.chatKey)) {
            done.insert(p.chatKey);
            post(p.base, envelope(QStringLiteral("nearby"), p.chatKey, t.toUtf8(), id));
        }
    if (done.isEmpty())
        Q_EMIT notice(QStringLiteral("Nobody nearby got that yet. They need Muninn or Huginn open close by or on the same Wi-Fi."));
}

void Chat::sendPrivate(const QString &key, const QString &text)
{
    const QString t = text.trimmed().left(kMax);
    if (t.isEmpty() || !m_ready)
        return;
    const QString name = nameOf(key);
    if (!isContact(key))
        meet(key, name);
    const QString r = route(key);
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QVariantMap mine = message(id, key, username(), t, true, r);
    mine.insert(QStringLiteral("state"), QStringLiteral("sending"));
    m_tries.insert(id, Tries{0, now() + kRetry[0]});
    addPrivate(key, mine);
    if (r.isEmpty() && backupMailbox(mine).isEmpty()) {
        update(key, id, [](QVariantMap &m) { m.insert(QStringLiteral("failed"), true); });
        Q_EMIT notice(isContact(key) ? name + QStringLiteral(" can't be reached: they're not nearby and have no internet mailbox yet.")
                                     : name + QStringLiteral(" is out of range. Say yes to Keep in touch to message from anywhere."));
        return;
    }
    attempt(key, id);
}

// Every message goes out on every route at once (Bluetooth, Wi-Fi and the internet); the other
// side keeps the first copy. Until "delivered" comes back it's sent again after 5 s, then less often.
void Chat::attempt(const QString &key, const QString &id)
{
    QVariantMap m;
    for (const QVariant &v : m_private.value(key))
        if (str(v.toMap(), "id") == id)
            m = v.toMap();
    if (m.isEmpty())
        return;
    Tries &t = m_tries[id];
    t.count++;
    t.next = now() + retryDelay(t.count);
    const bool photo = !str(m, "photo").isEmpty();
    const QList<QJsonObject> bodies = photo ? pieces(m) : QList<QJsonObject>{envelope(QStringLiteral("private"), key, str(m, "text").toUtf8(), id)};
    if (bodies.isEmpty())
        return;
    if (photo)
        t.next = qMax(t.next, now() + bodies.size() * 200);   // don't start again while a big one is on its way
    bool any = false;
    auto markSent = [this, key, id](const QString &how) {
        update(key, id, [how](QVariantMap &x) {
            if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < 2) {
                x.insert(QStringLiteral("state"), QStringLiteral("sent"));
                x.insert(QStringLiteral("failed"), false);
                if (str(x, "via").isEmpty())
                    x.insert(QStringLiteral("via"), how);
            }
        });
    };
    bool ble = true;
    for (const QJsonObject &b : bodies)
        ble = ble && m_ble->send(key, QJsonDocument(b).toJson(QJsonDocument::Compact));
    if (ble) {
        any = true;
        markSent(QStringLiteral("bluetooth"));
    }
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty()) {
        any = true;
        auto left = std::make_shared<int>(int(bodies.size()));
        auto good = std::make_shared<bool>(true);
        for (const QJsonObject &b : bodies)
            post(base, b, [this, key, id, left, good, photo](int code) {
                *good = *good && code == 200;
                if (--*left > 0 || !*good)
                    return;
                // Same Wi-Fi answers only once it has the message, so a text is delivered right here.
                update(key, id, [photo](QVariantMap &x) {
                    if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < (photo ? 2 : 3)) {
                        x.insert(QStringLiteral("state"), photo ? QStringLiteral("sent") : QStringLiteral("delivered"));
                        x.insert(QStringLiteral("via"), QStringLiteral("wifi"));
                        x.insert(QStringLiteral("failed"), false);
                    }
                });
            });
    }
    const QString mailbox = backupMailbox(m);
    // Big photos only go over the internet if the close routes didn't deliver them within 5 s.
    if (!mailbox.isEmpty() && (!photo || !any || t.count >= 2) && bodies.size() <= Photos::kInternetPieces) {
        for (const QJsonObject &b : bodies)
            m_nostr->publish(mailbox, wrap(key, QJsonDocument(b).toJson(QJsonDocument::Compact)));
        any = true;
        markSent(QStringLiteral("internet"));
    }
    if (!any && t.count == 1)
        update(key, id, [](QVariantMap &x) { x.insert(QStringLiteral("failed"), true); });
}

void Chat::retryLoop()
{
    const qint64 t0 = now();
    for (auto it = m_incoming.begin(); it != m_incoming.end();)
        it = t0 - it.value().started > 15 * 60000 ? m_incoming.erase(it) : std::next(it);   // half a photo that never finished
    // Someone said yes while out of range: say it again when they're back.
    for (const QString &k : QStringList(m_unsentKeeps.begin(), m_unsentKeeps.end()))
        if (!route(k).isEmpty() && !deliver(k, envelope(QStringLiteral("keep"), k, QByteArrayLiteral("yes"), QUuid::createUuid().toString(QUuid::WithoutBraces)), mailboxOf(k)).isEmpty())
            m_unsentKeeps.remove(k);
    QList<QPair<QString, QString>> due, unsends;
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        for (const QVariant &v : it.value()) {
            const QVariantMap m = v.toMap();
            const QString id = str(m, "id");
            if (!m.value(QStringLiteral("mine")).toBool())
                continue;
            if (str(m, "state") == QLatin1String("unsending")) {
                Tries &t = m_tries[id];
                if (t.next == 0)
                    t = Tries{1, t0 + kRetry[0]};
                if (t.count > 300) {   // about a day: their app may be too old to answer
                    unsends << qMakePair(it.key(), QStringLiteral("!") + id);
                    continue;
                }
                if (t0 >= t.next)
                    unsends << qMakePair(it.key(), id);
                continue;
            }
            // State "" is from before receipts existed: those were sent already, leave them be.
            if (str(m, "state").isEmpty() || !str(m, "unsent").isEmpty() || rank(str(m, "state")) >= 3 || t0 - m.value(QStringLiteral("time")).toLongLong() > kDay) {
                m_tries.remove(id);
                continue;
            }
            if (!m_tries.contains(id)) {
                m_tries.insert(id, Tries{1, t0 + kRetry[0]});   // after a restart: wait one round
                continue;
            }
            if (t0 >= m_tries.value(id).next)
                due << qMakePair(it.key(), id);
        }
    for (const auto &d : due)
        attempt(d.first, d.second);
    for (const auto &u : unsends) {
        if (u.second.startsWith(QLatin1Char('!'))) {
            const QString id = u.second.mid(1);
            update(u.first, id, [](QVariantMap &x) { x.insert(QStringLiteral("state"), QStringLiteral("unsent")); });
            m_tries.remove(id);
        } else {
            attemptUnsend(u.first, u.second);
        }
    }
}

void Chat::retry(const QString &key, const QString &id)
{
    m_tries.remove(id);
    update(key, id, [](QVariantMap &x) {
        x.insert(QStringLiteral("failed"), false);
        x.insert(QStringLiteral("state"), QStringLiteral("sending"));
        x.insert(QStringLiteral("time"), now());
    });
    attempt(key, id);
}

void Chat::receipt(const QString &key, const QStringList &ids, const QString &state, const QString &mailbox)
{
    if (ids.isEmpty())
        return;
    const QString box = mailbox.isEmpty() ? mailboxOf(key) : mailbox;
    // 40 ids a receipt keeps each one small on every route.
    const QStringList last = ids.mid(qMax(0, int(ids.size()) - 200));
    for (int i = 0; i < last.size(); i += 40) {
        const QJsonObject r{{QStringLiteral("ids"), QJsonArray::fromStringList(last.mid(i, 40))}, {QStringLiteral("s"), state}};
        deliver(key, envelope(QStringLiteral("receipt"), key, QJsonDocument(r).toJson(QJsonDocument::Compact), QUuid::createUuid().toString(QUuid::WithoutBraces)), box);
    }
}

void Chat::markRead(const QString &key)
{
    m_openChat = key;
    if (m_unread.remove(key))
        Q_EMIT changed();
    if (!readReceipts())
        return;
    QStringList fresh;
    QVariantList &l = m_private[key];
    for (QVariant &v : l) {
        QVariantMap m = v.toMap();
        if (!m.value(QStringLiteral("mine")).toBool() && str(m, "state") != QLatin1String("read")) {
            fresh << str(m, "id");
            m.insert(QStringLiteral("state"), QStringLiteral("read"));
            v = m;
        }
    }
    if (fresh.isEmpty())
        return;
    save();
    receipt(key, fresh, QStringLiteral("read"), QString());
}

void Chat::unsend(const QString &key, const QString &id)
{
    bool found = false;
    update(key, id, [this, &found](QVariantMap &m) {
        if (!m.value(QStringLiteral("mine")).toBool() || !str(m, "unsent").isEmpty())
            return;
        if (!str(m, "photo").isEmpty())
            m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
        m.insert(QStringLiteral("text"), QString());
        m.insert(QStringLiteral("photo"), QString());
        m.insert(QStringLiteral("voice"), 0);
        m.insert(QStringLiteral("unsent"), QStringLiteral("You"));
        m.insert(QStringLiteral("state"), QStringLiteral("unsending"));
        m.insert(QStringLiteral("failed"), false);
        found = true;
    });
    if (!found)
        return;
    m_tries.insert(id, Tries{0, 0});
    attemptUnsend(key, id);
}

void Chat::attemptUnsend(const QString &key, const QString &id)
{
    Tries &t = m_tries[id];
    t.count++;
    t.next = now() + retryDelay(t.count);
    const QJsonObject body = envelope(QStringLiteral("unsend"), key, id.toUtf8(), QUuid::createUuid().toString(QUuid::WithoutBraces));
    m_ble->send(key, QJsonDocument(body).toJson(QJsonDocument::Compact));
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty())
        post(base, body);
    const QString box = mailboxOf(key);
    if (!box.isEmpty())
        m_nostr->publish(box, wrap(key, QJsonDocument(body).toJson(QJsonDocument::Compact)));
}

// ------------------------------------------------------------------ keeping

bool Chat::known(const QString &key, const QString &id) const
{
    if (m_deleted.contains(id) || m_held.value(key).contains(id))
        return true;
    for (const QVariant &v : m_private.value(key))
        if (str(v.toMap(), "id") == id)
            return true;
    return false;
}

void Chat::forget(const QStringList &ids)
{
    const qint64 t = now();
    for (const QString &id : ids)
        m_deleted.insert(id, t);
    for (auto it = m_deleted.begin(); it != m_deleted.end();)
        it = t - it.value() > 2 * kDay ? m_deleted.erase(it) : std::next(it);
    QJsonObject o;
    for (auto it = m_deleted.begin(); it != m_deleted.end(); ++it)
        o.insert(it.key(), double(it.value()));
    m_vault->write(QStringLiteral("deleted.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool Chat::addPrivate(const QString &peer, const QVariantMap &m)
{
    const QString id = str(m, "id");
    if (m_deleted.contains(id))
        return false;
    QVariantList &l = m_private[peer];
    for (const QVariant &v : std::as_const(l))
        if (str(v.toMap(), "id") == id)
            return false;
    l << m;
    while (l.size() > 1000)
        l.removeFirst();
    save();
    Q_EMIT changed();
    return true;
}

void Chat::update(const QString &peer, const QString &id, const std::function<void(QVariantMap &)> &f)
{
    if (!m_private.contains(peer))
        return;
    QVariantList &l = m_private[peer];
    for (QVariant &v : l) {
        QVariantMap m = v.toMap();
        if (str(m, "id") == id) {
            f(m);
            v = m;
        }
    }
    save();
    Q_EMIT changed();
}

void Chat::addNearby(const QVariantMap &m)
{
    for (const QVariant &v : std::as_const(m_nearby))
        if (str(v.toMap(), "id") == str(m, "id"))
            return;
    m_nearby << m;
    while (m_nearby.size() > 300)
        m_nearby.removeFirst();
    if (keepNearby())
        save();
    Q_EMIT changed();
}

void Chat::deleteMessage(const QString &key, const QString &id)
{
    forget({id});
    QVariantList &l = m_private[key];
    for (int i = l.size() - 1; i >= 0; --i)
        if (str(l.at(i).toMap(), "id") == id) {
            const QString p = str(l.at(i).toMap(), "photo");
            if (!p.isEmpty())
                m_vault->remove(QStringLiteral("photos/") + p);
            l.removeAt(i);
        }
    save();
    Q_EMIT changed();
}

void Chat::deleteNearbyMessage(const QString &id)
{
    forget({id});
    for (int i = m_nearby.size() - 1; i >= 0; --i)
        if (str(m_nearby.at(i).toMap(), "id") == id)
            m_nearby.removeAt(i);
    if (keepNearby())
        save();
    Q_EMIT changed();
}

void Chat::deleteAll()
{
    m_private.clear();
    m_nearby.clear();
    QDir(m_vault->path(QStringLiteral("photos"))).removeRecursively();
    m_vault->remove(QStringLiteral("nearby.json"));
    save();
    Q_EMIT changed();
}

void Chat::prune()
{
    const qint64 age = deleteAfter();
    if (age <= 0 || !m_ready)
        return;
    const qint64 cutoff = now() - age;
    bool changedAny = false;
    for (auto it = m_private.begin(); it != m_private.end(); ++it) {
        QVariantList kept;
        for (const QVariant &v : it.value()) {
            const QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("time")).toLongLong() >= cutoff) {
                kept << v;
            } else {
                changedAny = true;
                if (!str(m, "photo").isEmpty())
                    m_vault->remove(QStringLiteral("photos/") + str(m, "photo"));
            }
        }
        it.value() = kept;
    }
    QVariantList n;
    for (const QVariant &v : std::as_const(m_nearby))
        if (v.toMap().value(QStringLiteral("time")).toLongLong() >= cutoff)
            n << v;
    changedAny = changedAny || n.size() != m_nearby.size();
    m_nearby = n;
    if (changedAny) {
        save();
        Q_EMIT changed();
    }
}

void Chat::save()
{
    QJsonObject priv;
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        priv.insert(it.key(), QJsonArray::fromVariantList(it.value()));
    m_vault->write(QStringLiteral("chats.json"), QJsonDocument(priv).toJson(QJsonDocument::Compact));
    if (keepNearby())
        m_vault->write(QStringLiteral("nearby.json"), QJsonDocument(QJsonArray::fromVariantList(m_nearby)).toJson(QJsonDocument::Compact));
}

void Chat::saveMet()
{
    QJsonObject o;
    for (auto it = m_met.begin(); it != m_met.end(); ++it)
        o.insert(it.key(), QJsonObject::fromVariantMap(it.value()));
    m_vault->write(QStringLiteral("met.json"), QJsonDocument(o).toJson(QJsonDocument::Compact));
}

void Chat::saveContacts()
{
    QJsonArray a;
    for (const QVariantMap &c : std::as_const(m_contacts))
        a << QJsonObject::fromVariantMap(c);
    m_vault->write(QStringLiteral("contacts.json"), QJsonDocument(a).toJson(QJsonDocument::Compact));
}

void Chat::load()
{
    const QJsonObject priv = QJsonDocument::fromJson(m_vault->read(QStringLiteral("chats.json"))).object();
    for (auto it = priv.begin(); it != priv.end(); ++it)
        m_private.insert(it.key(), it.value().toArray().toVariantList());
    // Before encryption, chats lived in a plain file (with a "private" section): bring them over.
    const QString old = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/chats.json");
    if (QFile::exists(old)) {
        QFile f(old);
        if (f.open(QIODevice::ReadOnly)) {
            const QJsonObject p = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("private")).toObject();
            for (auto it = p.begin(); it != p.end(); ++it)
                if (!m_private.contains(it.key()))
                    m_private.insert(it.key(), it.value().toArray().toVariantList());
            f.close();
        }
        QFile::remove(old);
        save();
    }
    // Older messages kept a display time ("8:41 PM"); from now on it's milliseconds.
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        for (QVariant &v : it.value()) {
            QVariantMap m = v.toMap();
            if (m.value(QStringLiteral("time")).typeId() == QMetaType::QString) {
                m.insert(QStringLiteral("time"), now());
                v = m;
            }
        }
    const QJsonObject met = QJsonDocument::fromJson(m_vault->read(QStringLiteral("met.json"))).object();
    for (auto it = met.begin(); it != met.end(); ++it)
        m_met.insert(it.key(), it.value().toObject().toVariantMap());
    const QJsonArray contacts = QJsonDocument::fromJson(m_vault->read(QStringLiteral("contacts.json"))).array();
    for (const QJsonValue &c : contacts)
        m_contacts.insert(c.toObject().value(QStringLiteral("key")).toString(), c.toObject().toVariantMap());
    const QJsonObject inv = QJsonDocument::fromJson(m_vault->read(QStringLiteral("invites.json"))).object();
    for (auto it = inv.begin(); it != inv.end(); ++it)
        m_invites.insert(it.key(), qint64(it.value().toDouble()));
    const QJsonObject del = QJsonDocument::fromJson(m_vault->read(QStringLiteral("deleted.json"))).object();
    for (auto it = del.begin(); it != del.end(); ++it)
        m_deleted.insert(it.key(), qint64(it.value().toDouble()));
    if (keepNearby())
        m_nearby = QJsonDocument::fromJson(m_vault->read(QStringLiteral("nearby.json"))).array().toVariantList();
    prune();
}

// ------------------------------------------------------------------ lists for the screen

QVariantList Chat::chats() const
{
    // Contacts, plus anyone else there's a conversation with (met close by, or removed as a contact).
    QSet<QString> keys;
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        keys.insert(it.key());
    for (auto it = m_private.begin(); it != m_private.end(); ++it)
        if (!it.value().isEmpty())
            keys.insert(it.key());
    QVariantList out;
    for (const QString &k : keys) {
        if (isBlocked(k))
            continue;
        const QVariantList msgs = m_private.value(k);
        const QVariantMap last = msgs.isEmpty() ? QVariantMap() : msgs.last().toMap();
        QString preview;
        if (!last.isEmpty()) {
            const bool mine = last.value(QStringLiteral("mine")).toBool();
            if (!str(last, "unsent").isEmpty())
                preview = QStringLiteral("Message deleted");
            else if (last.value(QStringLiteral("voice")).toInt() > 0)
                preview = mine ? QStringLiteral("You sent a voice message") : QStringLiteral("Voice message");
            else if (!str(last, "photo").isEmpty())
                preview = mine ? QStringLiteral("You sent a photo") : QStringLiteral("Photo");
            else
                preview = str(last, "text");
        }
        out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(k)}, {QStringLiteral("last"), preview},
                           {QStringLiteral("time"), last.value(QStringLiteral("time"), 0)}, {QStringLiteral("route"), route(k)},
                           {QStringLiteral("unread"), m_unread.contains(k) || shouldAskKeep(k)}, {QStringLiteral("contact"), isContact(k)}};
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("time")).toLongLong() > b.toMap().value(QStringLiteral("time")).toLongLong();
    });
    return out;
}

QVariantList Chat::requests() const
{
    QVariantList out;
    for (const QVariantMap &r : m_requests)
        out << r;
    return out;
}

QVariantList Chat::around() const
{
    // Everyone reachable right now that there's no chat with yet: Bluetooth first, then Wi-Fi.
    QVariantList out;
    QSet<QString> seen;
    for (const QVariant &v : m_ble->peers()) {
        const QString k = str(v.toMap(), "chatKey");
        if (k.isEmpty() || seen.contains(k) || isBlocked(k) || isContact(k) || !m_private.value(k).isEmpty())
            continue;
        seen.insert(k);
        out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), str(v.toMap(), "name")}, {QStringLiteral("route"), QStringLiteral("bluetooth")}};
    }
    for (const LocalSend::Peer &p : m_net->chatPeers()) {
        if (p.chatKey.isEmpty() || seen.contains(p.chatKey) || isBlocked(p.chatKey) || isContact(p.chatKey) || !m_private.value(p.chatKey).isEmpty())
            continue;
        seen.insert(p.chatKey);
        out << QVariantMap{{QStringLiteral("key"), p.chatKey}, {QStringLiteral("name"), p.chatName.isEmpty() ? p.alias : p.chatName}, {QStringLiteral("route"), QStringLiteral("wifi")}};
    }
    return out;
}

QVariantList Chat::people() const
{
    // Settings › Contacts: in touch, met close by, and blocked.
    QVariantList out;
    QSet<QString> seen;
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it) {
        seen.insert(it.key());
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), str(it.value(), "name")}, {QStringLiteral("contact"), true},
                           {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
    }
    for (auto it = m_met.begin(); it != m_met.end(); ++it)
        if (!seen.contains(it.key())) {
            seen.insert(it.key());
            out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), str(it.value(), "name")}, {QStringLiteral("contact"), false},
                               {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
        }
    for (const QString &k : m_settings.value(QStringLiteral("chat/muted")).toStringList())
        if (!seen.contains(k))
            out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(k)}, {QStringLiteral("contact"), false},
                               {QStringLiteral("verified"), false}, {QStringLiteral("blocked"), true}};
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return str(a.toMap(), "name").toLower() < str(b.toMap(), "name").toLower();
    });
    return out;
}

int Chat::nearbyCount() const
{
    QSet<QString> keys;
    for (const QVariant &v : m_ble->peers())
        keys.insert(str(v.toMap(), "chatKey"));
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty())
            keys.insert(p.chatKey);
    keys.remove(QString());
    return int(keys.size());
}
