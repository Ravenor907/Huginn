#include "chat.h"
#include "ble.h"
#include "chatcrypto.h"
#include "call/callcrypto.h"
#include "groupwire.h"
#include "localsend.h"
#include "nostr.h"
#include "photos.h"
#include "tor.h"
#include "vault.h"

#include <QBuffer>
#include <QClipboard>
#include <QCryptographicHash>
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
#include <QMetaProperty>
#include <QNetworkReply>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <algorithm>
#include <memory>

namespace
{
const QString kPath = QStringLiteral("/api/huginn/v1/message");
const QString kPrefix = QStringLiteral("MUNINN1:");
const QString kPage = QStringLiteral("https://ravenor907.github.io/Huginn-Muninn/add/#");
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
QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }
const Ratchet::NewKey kNewKey = [] { return ChatCrypto::newKeyPair(); };
// Our own message ids are UUIDs; anything else stays out of file names.
bool fileSafe(const QString &id)
{
    if (id.isEmpty() || id.size() > 80)
        return false;
    for (const QChar c : id)
        if (!(c.isLetterOrNumber() || c == QLatin1Char('-')))
            return false;
    return true;
}
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
    // Erase everything: the ratchet state and kept envelopes go with the vault's files.
    connect(m_vault, &Vault::changed, this, [this] {
        if (!m_vault->exists()) {
            m_peers.clear();
            m_capable.clear();
            m_caps.clear();
            m_outbox.clear();
            m_unsentKeeps.clear();
            m_groups.clear();
            m_gidx.clear();
            m_gpeers.clear();
            m_ginvites.clear();
            m_joining.clear();
            m_gtokens.clear();
            m_gout.clear();
            m_folders.clear();
        }
    });
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
        m_settings.setValue(QStringLiteral("chat/pub"), pub.toBase64());
        // Only drop the old copy once the vault really has it, or the identity could be lost.
        if (m_vault->write(QStringLiteral("secrets.json"), QJsonDocument(secrets).toJson(QJsonDocument::Compact)))
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
        if (!onion().isEmpty())
            o.insert(QStringLiteral("onion"), onion());
        return o;
    };
    m_net->chatReceive = [this](const QByteArray &body, const QString &) { return receive(body, QStringLiteral("wifi")); };
    connect(m_net, &LocalSend::devicesChanged, this, &Chat::changed);
    connect(m_net, &LocalSend::devicesChanged, this, &Chat::learnOnions);
    connect(m_ble, &Ble::message, this, [this](const QString &, const QByteArray &json) { receive(json, QStringLiteral("bluetooth")); });
    connect(m_ble, &Ble::changed, this, &Chat::changed);
    connect(m_ble, &Ble::changed, this, &Chat::learnOnions);
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
    connect(&m_prune, &QTimer::timeout, this, [this] {
        prune();
        sweepSealed();
        m_seen.prune(now(), 2 * kDay, 5000);
    });
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

QString Chat::code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite, qint64 expires, const QString &onion)
{
    QJsonObject o{{QStringLiteral("n"), name}, {QStringLiteral("k"), chatKey}, {QStringLiteral("p"), mailbox}};
    if (!invite.isEmpty()) {
        o.insert(QStringLiteral("i"), invite);
        o.insert(QStringLiteral("x"), double(expires / 1000));
    }
    // 0.6: where this device is reached over Tor. 0.5 apps ignore it.
    if (!onion.isEmpty())
        o.insert(QStringLiteral("o"), onion);
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
    m_link = kPage + code(username(), myKey(), m_nostr->publicKey(), token, m_linkExpires, onion());
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

QVariantMap Chat::parseCode(const QString &link) const
{
    // A bare code, or the add page's link with the code after "#" (some apps write its ":" as "%3A").
    QString text = link;
    text.replace(QStringLiteral("MUNINN1%3A"), kPrefix, Qt::CaseInsensitive);
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
    const QString onion = o.value(QStringLiteral("o")).toString();
    return {{QStringLiteral("key"), key}, {QStringLiteral("name"), name}, {QStringLiteral("mailbox"), mailbox.size() == 64 ? mailbox : QString()},
            {QStringLiteral("mine"), key == myKey()}, {QStringLiteral("invite"), o.value(QStringLiteral("i")).toString()},
            {QStringLiteral("expired"), expires > 0 && expires < now()}, {QStringLiteral("onion"), isOnion(onion) ? onion : QString()}};
}

void Chat::addContact(const QString &text, const QString &sameAs)
{
    const QVariantMap c = parseCode(text);
    if (c.isEmpty() || c.value(QStringLiteral("mine")).toBool() || c.value(QStringLiteral("expired")).toBool())
        return;
    const QString key = str(c, "key");
    // The same inbox as a contact but another key: that contact's key is being replaced, never
    // silently. So is one the person picked ("the same person" for a contact with that name).
    QString old = isContact(key) ? QString() : contactWithMailbox(str(c, "mailbox"), key);
    if (old.isEmpty() && !sameAs.isEmpty() && sameAs != key && isContact(sameAs))
        old = sameAs;
    if (!old.isEmpty()) {
        replaceKey(old, key, str(c, "name"), str(c, "mailbox"));
        m_contacts[key].insert(QStringLiteral("invite"), c.value(QStringLiteral("invite")));
        m_contacts[key].insert(QStringLiteral("onion"), c.value(QStringLiteral("onion")));
        saveContacts();
        return;
    }
    // Whoever gave you their code is who they say: the code itself carries their key.
    const QString nick = nicknameOf(key);
    QVariantMap entry{{QStringLiteral("key"), key}, {QStringLiteral("name"), c.value(QStringLiteral("name"))},
                      {QStringLiteral("mailbox"), c.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), true},
                      {QStringLiteral("invite"), c.value(QStringLiteral("invite"))}, {QStringLiteral("onion"), c.value(QStringLiteral("onion"))}};
    if (!nick.isEmpty())
        entry.insert(QStringLiteral("nick"), nick);
    m_contacts.insert(key, entry);
    m_met.remove(key);
    saveContacts();
    saveMet();
    Q_EMIT changed();
}

QVariantMap Chat::replaces(const QString &text) const
{
    const QVariantMap c = parseCode(text);
    const QString key = str(c, "key");
    if (c.isEmpty() || c.value(QStringLiteral("mine")).toBool() || isContact(key))
        return {};
    auto found = [this](const QString &k, bool sameInbox) {
        return QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), nameOf(k)}, {QStringLiteral("sameInbox"), sameInbox}};
    };
    const QString byMailbox = contactWithMailbox(str(c, "mailbox"), key);
    if (!byMailbox.isEmpty())
        return found(byMailbox, true);
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (it.key() != key && str(it.value(), "name").compare(str(c, "name"), Qt::CaseInsensitive) == 0)
            return found(it.key(), false);
    return {};
}

QString Chat::contactWithMailbox(const QString &mailbox, const QString &notKey) const
{
    if (mailbox.size() != 64)
        return QString();
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (it.key() != notKey && str(it.value(), "mailbox") == mailbox)
            return it.key();
    return QString();
}

bool Chat::keyChanged(const QString &key) const { return m_contacts.value(key).value(QStringLiteral("keyChanged")).toBool(); }

void Chat::replaceKey(const QString &oldKey, const QString &newKey, const QString &name, const QString &mailbox)
{
    if (oldKey == newKey || !m_contacts.contains(oldKey))
        return;
    QVariantMap c = m_contacts.take(oldKey);
    c.insert(QStringLiteral("key"), newKey);
    if (!name.isEmpty())
        c.insert(QStringLiteral("name"), name);
    if (!mailbox.isEmpty())
        c.insert(QStringLiteral("mailbox"), mailbox);
    c.insert(QStringLiteral("verified"), false);
    c.insert(QStringLiteral("keyChanged"), true);
    c.remove(QStringLiteral("invite"));
    if (str(c, "nick").isEmpty() && !str(m_met.value(newKey), "nick").isEmpty())
        c.insert(QStringLiteral("nick"), str(m_met.value(newKey), "nick"));
    m_contacts.insert(newKey, c);
    m_met.remove(oldKey);
    m_met.remove(newKey);
    // The conversation stays. Whatever was still on its way to the old key stops: Try again
    // sends it to the new one, so nothing goes to the new key without the person choosing to.
    QVariantList msgs = m_private.take(oldKey);
    msgs += m_private.take(newKey);
    for (QVariant &v : msgs) {
        QVariantMap m = v.toMap();
        m.insert(QStringLiteral("peer"), newKey);
        const QString id = str(m, "id");
        if (m.value(QStringLiteral("mine")).toBool()) {
            if (str(m, "state") == QLatin1String("unsending")) {
                m.insert(QStringLiteral("state"), QStringLiteral("unsent"));
            } else if (str(m, "unsent").isEmpty() && (rank(str(m, "state")) == 1 || rank(str(m, "state")) == 2)) {
                m.insert(QStringLiteral("state"), QString());
                m.insert(QStringLiteral("failed"), true);
            }
            m_tries.remove(id);
            dropSealed(id);
            dropSealed(QStringLiteral("u-") + id);
        }
        v = m;
    }
    std::stable_sort(msgs.begin(), msgs.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("time")).toLongLong() < b.toMap().value(QStringLiteral("time")).toLongLong();
    });
    m_private.insert(newKey, msgs);
    if (m_unread.remove(oldKey))
        m_unread.insert(newKey);
    m_unsentKeeps.remove(oldKey);
    m_requests.remove(newKey);
    m_held.remove(newKey);
    if (m_openChat == oldKey)
        m_openChat = newKey;
    forgetRatchet(oldKey);
    forgetRatchet(newKey);
    saveContacts();
    saveMet();
    const QString text = QStringLiteral("%1's security key changed. Check the code with them before trusting new messages.").arg(nameOf(newKey));
    systemNote(newKey, text);
    Q_EMIT notice(text);
    Q_EMIT changed();
}

// A line in the conversation from Huginn itself (not from them, never sent anywhere).
void Chat::systemNote(const QString &key, const QString &text)
{
    QVariantMap m = message(QUuid::createUuid().toString(QUuid::WithoutBraces), key, QString(), text, false, QString());
    m.insert(QStringLiteral("system"), true);
    m.insert(QStringLiteral("state"), QStringLiteral("read"));   // never answered with a receipt
    addPrivate(key, m);
}

QByteArray Chat::keyWith(const QString &theirKey) const
{
    return ChatCrypto::sharedKey(m_priv, m_pub, QByteArray::fromBase64(theirKey.toLatin1()));
}

QString Chat::codeWith(const QString &key) const { return ChatCrypto::pairingCode(keyWith(key)); }

QString Chat::nameOf(const QString &key) const
{
    const QString nick = nicknameOf(key);
    return nick.isEmpty() ? realNameOf(key) : nick;
}

QString Chat::nicknameOf(const QString &key) const
{
    const QString n = str(m_contacts.value(key), "nick");
    return n.isEmpty() ? str(m_met.value(key), "nick") : n;
}

void Chat::setNickname(const QString &key, const QString &nick)
{
    if (key.isEmpty() || !m_ready)
        return;
    const QString t = nick.simplified().left(40);
    if (m_contacts.contains(key)) {
        if (t.isEmpty())
            m_contacts[key].remove(QStringLiteral("nick"));
        else
            m_contacts[key].insert(QStringLiteral("nick"), t);
        saveContacts();
    } else {
        // Someone met close by (or a chat kept after removing them): kept with what's known about them.
        QVariantMap m = m_met.value(key, {{QStringLiteral("name"), realNameOf(key)}, {QStringLiteral("dismissed"), true}});
        if (t.isEmpty())
            m.remove(QStringLiteral("nick"));
        else
            m.insert(QStringLiteral("nick"), t);
        putMet(key, m);
    }
    Q_EMIT changed();
}

QString Chat::realNameOf(const QString &key) const
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
    QString c = str(m_contacts.value(key), "mailbox");
    if (c.isEmpty())
        c = str(m_met.value(key), "mailbox");
    if (c.isEmpty())
        c = str(m_gpeers.value(key), "mailbox");
    if (c.isEmpty())
        for (const QVariantMap &j : m_joining)
            if (str(j, "admin") == key)
                c = str(j, "mailbox");
    return c;
}

QString Chat::onionOf(const QString &key) const
{
    QString o = str(m_contacts.value(key), "onion");
    if (o.isEmpty())
        o = str(m_gpeers.value(key), "onion");
    if (o.isEmpty())
        for (const QVariantMap &j : m_joining)
            if (str(j, "admin") == key)
                o = str(j, "onion");
    return isOnion(o) ? o : QString();
}

bool Chat::isOnion(const QString &o)
{
    // A v3 onion address: 56 base32 letters, with or without ".onion".
    const QString h = o.endsWith(QLatin1String(".onion")) ? o.left(o.size() - 6) : o;
    if (h.size() != 56)
        return false;
    for (const QChar c : h)
        if (!((c >= QLatin1Char('a') && c <= QLatin1Char('z')) || (c >= QLatin1Char('2') && c <= QLatin1Char('7'))))
            return false;
    return true;
}

// Hellos (Bluetooth, Wi-Fi) say where a contact is reached over Tor now. Only for people already
// known; a wrong one can only delay a message (the relays still get it from the second try on).
void Chat::learnOnions()
{
    if (!m_ready)
        return;
    bool any = false;
    auto learn = [this, &any](const QString &key, const QString &onion) {
        if (key.isEmpty() || !isOnion(onion))
            return;
        if (m_contacts.contains(key) && str(m_contacts.value(key), "onion") != onion) {
            m_contacts[key].insert(QStringLiteral("onion"), onion);
            any = true;
        } else if (m_gpeers.contains(key) && str(m_gpeers.value(key), "onion") != onion) {
            m_gpeers[key].insert(QStringLiteral("onion"), onion);
            saveGroups();
        }
    };
    for (const QVariant &p : m_ble->peers())
        learn(str(p.toMap(), "chatKey"), str(p.toMap(), "onion"));
    for (const LocalSend::Peer &p : m_net->chatPeers())
        learn(p.chatKey, p.onion);
    if (any)
        saveContacts();
}

// ------------------------------------------------------------------ Tor

void Chat::setTor(Tor *tor)
{
    m_tor = tor;
    if (!tor)
        return;
    // Messages for our onion address: the same as the Wi-Fi endpoint, but from far away.
    tor->onMessage([this](const QByteArray &body) { return receive(body, QStringLiteral("internet")); });
    // Its progress and address show on screen and go in our hellos.
    const QMetaObject *mo = tor->metaObject();
    const int slot = metaObject()->indexOfMethod("torChanged()");
    for (int i = mo->propertyOffset(); i < mo->propertyCount(); ++i) {
        const QMetaProperty p = mo->property(i);
        if (p.hasNotifySignal() && slot >= 0)
            QObject::connect(tor, p.notifySignal(), this, metaObject()->method(slot));
    }
    Q_EMIT changed();
}

void Chat::torChanged()
{
    const QString o = onion();
    if (o != m_lastOnion) {
        m_lastOnion = o;
        if (m_ready) {
            m_net->announce();
            m_ble->sayHello();
        }
    }
    Q_EMIT changed();
}

QString Chat::onion() const { return m_tor ? m_tor->onion() : QString(); }
bool Chat::torReady() const { return m_tor && m_tor->property("ready").toBool(); }
int Chat::torProgress() const { return m_tor ? qBound(0, m_tor->property("progress").toInt(), 100) : 0; }

QString Chat::netStatus() const
{
    if (m_tor) {
        if (torReady())
            return QStringLiteral("Online privately");
        return QStringLiteral("Connecting privately %1%").arg(torProgress());
    }
    return internet() > 0 ? QStringLiteral("Internet: connected") : QStringLiteral("Internet: offline");
}

bool Chat::sendInternet(const QString &key, const QList<QByteArray> &bodies, const QString &mailbox, bool alsoMailbox, std::function<void()> sent)
{
    if (bodies.isEmpty())
        return false;
    const QString onion = onionOf(key);
    auto relays = [this, key, bodies, mailbox] {
        for (const QByteArray &b : bodies)
            m_nostr->publish(mailbox, wrap(key, b));
    };
    if (!onion.isEmpty() && torReady()) {
        auto left = std::make_shared<int>(int(bodies.size()));
        auto good = std::make_shared<bool>(true);
        const bool relaysNow = alsoMailbox && !mailbox.isEmpty();
        for (const QByteArray &b : bodies)
            m_tor->post(onion, kPath, b, [left, good, relays, relaysNow, mailbox, sent](int code) {
                *good = *good && code == 200;
                if (--*left > 0)
                    return;
                if (*good) {
                    if (sent)
                        sent();
                } else if (!relaysNow && !mailbox.isEmpty()) {
                    relays();   // not there over Tor: their mailbox keeps it for them
                    if (sent)
                        sent();
                }
            });
        if (relaysNow)
            relays();
        return true;
    }
    if (mailbox.isEmpty())
        return false;
    relays();
    if (sent)
        sent();
    return true;
}

// ------------------------------------------------------------------ caps

bool Chat::canGroup(const QString &key) const { return isGroupPeer(key) || m_caps.value(key).contains(QStringLiteral("g1")); }

void Chat::noteCaps(const QString &key, bool present, const QStringList &caps)
{
    // From authentic envelopes only, and only for people kept here. An envelope without "caps"
    // (a 0.5 app) teaches nothing; tokens are never taken away (a key change starts over:
    // forgetRatchet).
    if (!present || (!isContact(key) && !m_met.contains(key) && !isGroupPeer(key)))
        return;
    const bool known = m_caps.contains(key);
    QStringList have = m_caps.value(key);
    bool grew = false;
    for (const QString &c : caps)
        if (!have.contains(c)) {
            have << c;
            grew = true;
        }
    if (known && !grew)
        return;
    m_caps.insert(key, have);
    saveRatchet(key);
    Q_EMIT changed();
}

void Chat::probeCaps(const QString &key)
{
    if (!m_ready || key.isEmpty() || capsKnown(key) || now() - m_probed.value(key, 0) < 3600000)
        return;
    m_probed.insert(key, now());
    const QJsonObject r{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}, {QStringLiteral("q"), QStringLiteral("caps")}};
    deliver(key, envelope(QStringLiteral("receipt"), key, compact(r), QUuid::createUuid().toString(QUuid::WithoutBraces)), mailboxOf(key));
}

// ------------------------------------------------------------------ calls

void Chat::sendCall(const QString &key, const QJsonObject &msg)
{
    if (!m_ready || key.isEmpty() || isBlocked(key))
        return;
    // Sealed once (a ratchet message moves the session on) and the same copy sent on every
    // route at once: the other side keeps the first and drops the rest. Never kept for resending.
    const QByteArray json = compact(envelope(QStringLiteral("call"), key, compact(msg), QUuid::createUuid().toString(QUuid::WithoutBraces)));
    m_ble->send(key, json);
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty())
        post(base, json);
    sendInternet(key, {json}, mailboxOf(key), false);
}

QByteArray Chat::signWithChatKey(const QByteArray &bytes) const { return CallCrypto::signDer(m_priv, bytes); }

bool Chat::canCall(const QString &key) const
{
    if (key.isEmpty() || isBlocked(key))
        return false;
    if (m_caps.value(key).contains(QStringLiteral("c1")))
        return isContact(key) || isGroupPeer(key);
    // A group peer whose caps aren't known yet counts as "r1 g1 c1" (docs/protocol-0.6.md 3.7, as Muninn does).
    return isGroupPeer(key) && !isContact(key) && !capsKnown(key);
}

QString Chat::lanHost(const QString &key) const
{
    const QString base = m_net->baseFor(key);
    return base.isEmpty() ? QString() : QUrl(base).host();
}

// ------------------------------------------------------------------ contact folders

void Chat::loadFolders()
{
    m_folders.clear();
    const QByteArray raw = m_vault->read(QStringLiteral("folders.json"));
    if (raw.isEmpty()) {
        // First time: three to start with. Rename, delete or add your own.
        for (const char *n : {"Family", "Work", "Kindred"})
            m_folders << QVariantMap{{QStringLiteral("id"), QUuid::createUuid().toString(QUuid::Id128).left(12)}, {QStringLiteral("name"), QString::fromLatin1(n)},
                                     {QStringLiteral("keys"), QStringList()}};
        saveFolders();
        return;
    }
    const QJsonArray a = QJsonDocument::fromJson(raw).object().value(QStringLiteral("folders")).toArray();
    for (const QJsonValue &v : a)
        m_folders << v.toObject().toVariantMap();
}

void Chat::saveFolders()
{
    QJsonArray a;
    for (const QVariantMap &f : std::as_const(m_folders))
        a << QJsonObject::fromVariantMap(f);
    m_vault->write(QStringLiteral("folders.json"), compact(QJsonObject{{QStringLiteral("folders"), a}}));
}

QVariantList Chat::folders() const
{
    QVariantList out;
    for (const QVariantMap &f : m_folders)
        out << QVariantMap{{QStringLiteral("id"), f.value(QStringLiteral("id"))}, {QStringLiteral("name"), f.value(QStringLiteral("name"))},
                           {QStringLiteral("count"), f.value(QStringLiteral("keys")).toStringList().size()}};
    return out;
}

QString Chat::addFolder(const QString &name)
{
    const QString n = name.simplified().left(24);
    if (n.isEmpty() || !m_ready || m_folders.size() >= 30)
        return QString();
    const QString id = QUuid::createUuid().toString(QUuid::Id128).left(12);
    m_folders << QVariantMap{{QStringLiteral("id"), id}, {QStringLiteral("name"), n}, {QStringLiteral("keys"), QStringList()}};
    saveFolders();
    Q_EMIT changed();
    return id;
}

void Chat::renameFolder(const QString &id, const QString &name)
{
    const QString n = name.simplified().left(24);
    if (n.isEmpty())
        return;
    for (QVariantMap &f : m_folders)
        if (str(f, "id") == id)
            f.insert(QStringLiteral("name"), n);
    saveFolders();
    Q_EMIT changed();
}

void Chat::deleteFolder(const QString &id)
{
    for (int i = int(m_folders.size()) - 1; i >= 0; --i)
        if (str(m_folders.at(i), "id") == id)
            m_folders.removeAt(i);
    saveFolders();
    Q_EMIT changed();
}

void Chat::setInFolder(const QString &id, const QString &key, bool on)
{
    for (QVariantMap &f : m_folders) {
        if (str(f, "id") != id)
            continue;
        QStringList keys = f.value(QStringLiteral("keys")).toStringList();
        keys.removeAll(key);
        if (on)
            keys << key;
        f.insert(QStringLiteral("keys"), keys);
    }
    saveFolders();
    Q_EMIT changed();
}

QStringList Chat::foldersOf(const QString &key) const
{
    QStringList out;
    for (const QVariantMap &f : m_folders)
        if (f.value(QStringLiteral("keys")).toStringList().contains(key))
            out << str(f, "id");
    return out;
}

QString Chat::route(const QString &key) const
{
    for (const QVariant &p : m_ble->peers())
        if (str(p.toMap(), "chatKey") == key)
            return QStringLiteral("bluetooth");
    if (!m_net->baseFor(key).isEmpty())
        return QStringLiteral("wifi");
    if (!str(m_contacts.value(key), "mailbox").isEmpty() || !str(m_gpeers.value(key), "mailbox").isEmpty() || !onionOf(key).isEmpty())
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
        m_contacts[key].remove(QStringLiteral("keyChanged"));
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
    // Sealed once: if nobody's in reach now, these same bytes go when they are.
    const QJsonObject keep = envelope(QStringLiteral("keep"), key, QByteArrayLiteral("yes"), QUuid::createUuid().toString(QUuid::WithoutBraces));
    if (deliver(key, keep, str(m, "mailbox")).isEmpty())
        m_unsentKeeps.insert(key, keep);
    if (m.value(QStringLiteral("theySaid")).toBool())
        becomeContacts(key);
    else
        Q_EMIT notice(QStringLiteral("Asked %1. Once they say yes too, you can message from anywhere.").arg(nameOf(key)));
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
    QVariantMap c{{QStringLiteral("key"), key}, {QStringLiteral("name"), m.value(QStringLiteral("name"))},
                  {QStringLiteral("mailbox"), m.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), m.value(QStringLiteral("verified"))}};
    if (!str(m, "nick").isEmpty())
        c.insert(QStringLiteral("nick"), m.value(QStringLiteral("nick")));
    m_contacts.insert(key, c);
    saveContacts();
    saveMet();
    Q_EMIT notice(QStringLiteral("You and %1 are in touch. Message each other from anywhere.").arg(nameOf(key)));
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
    if (r.isEmpty())
        return;
    const QVariantMap held = m_held.take(key);
    const QString old = str(r, "replaces");
    if (!old.isEmpty() && isContact(old)) {
        // They said they're a contact with a new security key, and the person accepted.
        replaceKey(old, key, str(r, "name"), str(r, "mailbox"));
    } else {
        m_contacts.insert(key, {{QStringLiteral("key"), key}, {QStringLiteral("name"), r.value(QStringLiteral("name"))},
                                {QStringLiteral("mailbox"), r.value(QStringLiteral("mailbox"))}, {QStringLiteral("verified"), false}});
        saveContacts();
    }
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
    m_unsentKeeps.remove(key);
    forgetRatchet(key);
    for (QVariantMap &f : m_folders) {
        QStringList keys = f.value(QStringLiteral("keys")).toStringList();
        if (keys.removeAll(key))
            f.insert(QStringLiteral("keys"), keys);
    }
    saveFolders();
    saveContacts();
    saveMet();
    refreshGroupPeers();   // someone you share a group with stays reachable there
    Q_EMIT changed();
}

// ------------------------------------------------------------------ receiving

int Chat::receive(const QByteArray &body, const QString &via)
{
    if (!m_ready)
        return 503;
    m_pendingPeer.reset();
    m_openedFrom.clear();
    const int code = receiveEnvelope(QJsonDocument::fromJson(body).object(), via);
    // A ratchet message that wasn't accepted leaves no trace, so the same envelope can open later.
    m_pendingPeer.reset();
    if (code == 200 && !m_openedFrom.isEmpty()) {
        noteCapable(m_openedFrom, m_openedCap);
        noteCaps(m_openedFrom, m_openedHasCaps, m_openedCaps);
        // Muninn and Huginn 0.6 put their onion address in every envelope ("o"): keep it current.
        const QString key = m_openedFrom, onionNow = m_openedOnion;
        if (isOnion(onionNow)) {
            if (m_contacts.contains(key) && str(m_contacts.value(key), "onion") != onionNow) {
                m_contacts[key].insert(QStringLiteral("onion"), onionNow);
                saveContacts();
            } else if (m_gpeers.contains(key) && str(m_gpeers.value(key), "onion") != onionNow) {
                m_gpeers[key].insert(QStringLiteral("onion"), onionNow);
                saveGroups();
            }
        }
    }
    m_openedOnion.clear();
    m_openedFrom.clear();
    return code;
}

// Opens v1 or r1. An r1 message's peer is only kept by commitPeer(), once the message is accepted
// and before anything is sent back (a reply moves the same peer on).
QByteArray Chat::open(const QJsonObject &o, const QString &fromKey, bool *ok)
{
    *ok = false;
    const QByteArray sk = keyWith(fromKey);
    if (sk.isEmpty())
        return QByteArray();
    const ChatCrypto::KeyPair me{m_priv, m_pub};
    QByteArray plain;
    if (ChatWire::isRatchet(o)) {
        Ratchet::Peer work = m_peers.value(fromKey);
        plain = ChatWire::open(o, sk, me, &work, kNewKey, ok);
        if (*ok) {
            m_pendingKey = fromKey;
            m_pendingPeer = work;
        }
    } else {
        plain = ChatWire::open(o, sk, me, nullptr, kNewKey, ok);
    }
    if (*ok) {
        m_openedFrom = fromKey;
        m_openedCap = ChatWire::saysCapable(o);
        m_openedCaps = ChatWire::capsOf(o, &m_openedHasCaps);
        m_openedOnion = o.value(QStringLiteral("o")).toString();
    }
    return *ok ? plain : QByteArray();
}

void Chat::commitPeer()
{
    if (!m_pendingPeer)
        return;
    m_peers.insert(m_pendingKey, *m_pendingPeer);
    m_pendingPeer.reset();
    saveRatchet(m_pendingKey);
}

void Chat::noteCapable(const QString &key, bool capable)
{
    // Only from a message that proved it's really them; only kept for people we know.
    if (!capable || m_capable.value(key) || (!isContact(key) && !m_met.contains(key) && !isGroupPeer(key)))
        return;
    m_capable.insert(key, true);
    saveRatchet(key);
}

int Chat::receiveEnvelope(const QJsonObject &o, const QString &via)
{
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
    if (room == QLatin1String("group") || room == QLatin1String("gmsg") || room == QLatin1String("greceipt"))
        return receiveGroup(o, fromKey, name, mailbox, via);
    if (room == QLatin1String("gphoto"))
        return receiveGroupPiece(o, fromKey, name, mailbox, via);
    const QString sentId = o.value(QStringLiteral("id")).toString();
    // A copy that's already here (another route, or a resend): confirm again, don't count it.
    // Always before opening: a ratchet message only opens once.
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
    const bool once = room == QLatin1String("receipt") || room == QLatin1String("unsend") || room == QLatin1String("keep") || room == QLatin1String("call");
    if (once && m_seen.has(fromKey, sentId)) {
        // An unsend's resend: say "unsent" again in case the first answer was lost.
        const QString gone = m_seen.note(fromKey, sentId);
        if (room == QLatin1String("unsend") && !gone.isEmpty())
            receipt(fromKey, {gone}, QStringLiteral("unsent"), mailbox);
        return 200;
    }
    bool ok = false;
    const QByteArray plain = open(o, fromKey, &ok);
    if (!ok)
        return keyWith(fromKey).isEmpty() ? 400 : 403;
    // Calls (0.6): only from contacts and group peers, at most 30 signals in 10 seconds each.
    if (room == QLatin1String("call")) {
        if (!isContact(fromKey) && !isGroupPeer(fromKey)) {
            m_pendingPeer.reset();
            return 403;
        }
        if (m_recentCall.size() > 500)
            m_recentCall.clear();
        QList<qint64> &q = m_recentCall[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= 30) {
            m_pendingPeer.reset();
            return 429;
        }
        q << now();
        const QJsonObject msg = QJsonDocument::fromJson(plain).object();
        if (msg.isEmpty()) {
            m_pendingPeer.reset();
            return 400;
        }
        commitPeer();
        if (!sentId.isEmpty())
            m_seen.add(fromKey, sentId, now());   // the same signal on another route is dropped
        Q_EMIT callMessage(fromKey, msg);
        return 200;
    }
    // No more than 5 messages in 10 seconds from anyone, so nobody can flood a room. Counted only
    // once a message proved it's really from them, so nobody can use it to block a real contact.
    if (room != QLatin1String("receipt")) {
        if (m_recent.size() > 500)
            m_recent.clear();
        QList<qint64> &q = m_recent[fromKey];
        while (!q.isEmpty() && now() - q.first() > 10000)
            q.removeFirst();
        if (q.size() >= 5)
            return 429;
        q << now();
    }
    const QString text = room == QLatin1String("receipt") ? QString::fromUtf8(plain) : QString::fromUtf8(plain).left(kMax);
    const QString msgId = sentId.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces) : sentId;

    if (room == QLatin1String("nearby")) {
        if (!nearbyOn() || via == QLatin1String("internet"))
            return 403;
        commitPeer();
        addNearby(message(msgId, fromKey, name, text, false, via));
        return 200;
    }
    if (room == QLatin1String("private")) {
        const QString claims = isContact(fromKey) || m_met.contains(fromKey) ? QString() : contactWithMailbox(mailbox, fromKey);
        if (isContact(fromKey)) {
            commitPeer();
            if (!mailbox.isEmpty() && str(m_contacts.value(fromKey), "mailbox") != mailbox) {
                m_contacts[fromKey].insert(QStringLiteral("mailbox"), mailbox);
                saveContacts();
            }
            if (addPrivate(fromKey, message(msgId, fromKey, name, text, false, via))) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(nameOf(fromKey), text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (!claims.isEmpty()) {
            // A new key with a contact's inbox: maybe that contact with a new security key, maybe
            // not. Never taken on quietly: held until the person accepts (and is warned).
            commitPeer();
            m_held[fromKey].insert(msgId, message(msgId, fromKey, name, text, false, via));
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name},
                                            {QStringLiteral("code"), codeWith(fromKey)}, {QStringLiteral("mailbox"), mailbox},
                                            {QStringLiteral("replaces"), claims}});
                Q_EMIT notice(QStringLiteral("Someone says they're %1 with a new security key. Check Chat before accepting.").arg(nameOf(claims)));
                Q_EMIT arrived(nameOf(claims), QStringLiteral("Says their security key changed"), fromKey);
            }
        } else if (via != QLatin1String("internet") || m_met.contains(fromKey)) {
            // Close by or on the same Wi-Fi (or someone met that way): no asking first.
            commitPeer();
            QVariantMap m = m_met.value(fromKey);
            m.insert(QStringLiteral("name"), name);
            if (!mailbox.isEmpty())
                m.insert(QStringLiteral("mailbox"), mailbox);
            putMet(fromKey, m);
            if (addPrivate(fromKey, message(msgId, fromKey, name, text, false, via))) {
                m_unread.insert(fromKey);
                Q_EMIT arrived(nameOf(fromKey), text, fromKey);
            }
            receipt(fromKey, {msgId}, QStringLiteral("delivered"), mailbox);
        } else if (!inviteOk(o.value(QStringLiteral("invite")).toString())) {
            return 403;   // from far away with no valid invite (an old or leaked code): dropped quietly
        } else {
            // Someone with this computer's code, from far away: ask first.
            commitPeer();
            m_held[fromKey].insert(msgId, message(msgId, fromKey, name, text, false, via));
            if (!m_requests.contains(fromKey)) {
                m_requests.insert(fromKey, {{QStringLiteral("key"), fromKey}, {QStringLiteral("name"), name},
                                            {QStringLiteral("code"), codeWith(fromKey)}, {QStringLiteral("mailbox"), mailbox}});
                Q_EMIT notice(name + QStringLiteral(" wants to chat"));
                Q_EMIT arrived(name, QStringLiteral("Wants to chat with you"), fromKey);
            }
        }
        Q_EMIT changed();
        return 200;
    }
    if (room == QLatin1String("receipt")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey) && !isGroupPeer(fromKey))
            return 403;
        const QJsonObject r = QJsonDocument::fromJson(text.toUtf8()).object();
        const QString st = r.value(QStringLiteral("s")).toString();
        if (st != QLatin1String("delivered") && st != QLatin1String("read") && st != QLatin1String("unsent"))
            return 400;
        commitPeer();
        m_seen.add(fromKey, sentId, now());
        // A caps probe (0.6): answer with an empty receipt, whose envelope carries our caps.
        if (r.value(QStringLiteral("q")).toString() == QLatin1String("caps")) {
            const QJsonObject a{{QStringLiteral("ids"), QJsonArray()}, {QStringLiteral("s"), QStringLiteral("delivered")}};
            deliver(fromKey, envelope(QStringLiteral("receipt"), fromKey, compact(a), QUuid::createUuid().toString(QUuid::WithoutBraces)),
                    mailbox.isEmpty() ? mailboxOf(fromKey) : mailbox);
        }
        QSet<QString> ids;
        const QJsonArray a = r.value(QStringLiteral("ids")).toArray();
        for (int i = 0; i < qMin(int(a.size()), 200); ++i)
            ids.insert(a.at(i).toString());
        QVariantList &l = m_private[fromKey];
        bool any = false;
        QStringList done;
        for (QVariant &v : l) {
            QVariantMap m = v.toMap();
            if (!m.value(QStringLiteral("mine")).toBool() || !ids.contains(str(m, "id")))
                continue;
            if (st == QLatin1String("unsent")) {
                if (str(m, "state") == QLatin1String("unsending")) {
                    m.insert(QStringLiteral("state"), QStringLiteral("unsent"));
                    m_tries.remove(str(m, "id"));
                    done << QStringLiteral("u-") + str(m, "id");
                }
            } else if (str(m, "unsent").isEmpty() && rank(st) > rank(str(m, "state"))) {
                m.insert(QStringLiteral("state"), st);
                m.insert(QStringLiteral("failed"), false);
                done << str(m, "id");
            }
            v = m;
            any = true;
        }
        for (const QString &id : std::as_const(done))
            dropSealed(id);   // there: no more resends
        if (any) {
            save();
            Q_EMIT changed();
        }
        return 200;
    }
    if (room == QLatin1String("unsend")) {
        if (!isContact(fromKey) && !m_met.contains(fromKey))
            return 403;
        commitPeer();
        const QString gone = text.trimmed();
        m_seen.add(fromKey, sentId, now(), gone);
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
            m.insert(QStringLiteral("unsent"), name);   // shown with their current name or nickname (messagesWith)
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
        if (isContact(fromKey)) {
            commitPeer();
            m_seen.add(fromKey, sentId, now());
            return 200;
        }
        if (!m_met.contains(fromKey) && (via == QLatin1String("internet") || !contactWithMailbox(mailbox, fromKey).isEmpty()))
            return 403;   // a new key with a contact's inbox goes through a request (a private message) instead
        commitPeer();
        m_seen.add(fromKey, sentId, now());
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
            Q_EMIT notice(nameOf(fromKey) + QStringLiteral(" wants to keep in touch"));
            Q_EMIT arrived(nameOf(fromKey), QStringLiteral("Wants to keep in touch"), fromKey);
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
    if (key.startsWith(QLatin1String("g:"))) {
        const QStringList parts = key.split(QLatin1Char(':'));
        return parts.size() == 3 && sendGroupPhoto(parts.at(1), parts.at(2), path, hd);
    }
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
    // A group channel: "g:<group>:<channel>" (the group page records for it).
    if (key.startsWith(QLatin1String("g:"))) {
        const QStringList parts = key.split(QLatin1Char(':'));
        if (parts.size() == 3)
            sendGroupBlob(parts.at(1), parts.at(2), aac, qBound(1, ms, Photos::kMaxVoiceMs));
        return;
    }
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
#ifdef Q_OS_WIN
    // Steam on Windows says where it lives (usually C:/Program Files (x86)/Steam).
    const QString steam = QSettings(QStringLiteral("HKEY_CURRENT_USER\\Software\\Valve\\Steam"), QSettings::NativeFormat)
                              .value(QStringLiteral("SteamPath")).toString();
    if (!steam.isEmpty())
        roots.prepend(QDir::fromNativeSeparators(steam) + QStringLiteral("/userdata"));
#endif
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

void Chat::copyText(const QString &text) const
{
    if (auto *c = QGuiApplication::clipboard())
        c->setText(text);
}

QList<QJsonObject> Chat::pieces(const QVariantMap &m)
{
    const QByteArray bytes = blob(str(m, "photo"));
    if (bytes.isEmpty())
        return {};
    const QString peer = str(m, "peer");
    const QList<QByteArray> parts = Photos::split(bytes);
    QList<QJsonObject> out;
    const int voice = m.value(QStringLiteral("voice")).toInt();
    for (int i = 0; i < parts.size(); ++i) {
        QJsonObject o{{QStringLiteral("room"), QStringLiteral("photo")}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                      {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), str(m, "id")}, {QStringLiteral("i"), i},
                      {QStringLiteral("n"), int(parts.size())}};
        if (voice > 0) {
            o.insert(QStringLiteral("kind"), QStringLiteral("voice"));
            o.insert(QStringLiteral("ms"), voice);
        }
        out << seal(o, peer, parts.at(i));   // each piece is its own message (r1: its own ratchet step)
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
    // A piece already here: dropped before opening (a ratchet message only opens once).
    const auto have = m_incoming.constFind(id);
    if (have != m_incoming.cend() && have->from == fromKey && i < have->parts.size() && !have->parts.at(i).isEmpty())
        return 200;
    // A new key with a contact's inbox: only through a request (see receive).
    if (!contact && !m_met.contains(fromKey) && !contactWithMailbox(mailbox, fromKey).isEmpty())
        return 403;
    if (m_pieceTimes.size() > 500)
        m_pieceTimes.clear();
    QList<qint64> &q = m_pieceTimes[fromKey];
    while (!q.isEmpty() && now() - q.first() > 60000)
        q.removeFirst();
    if (q.size() >= 1000)
        return 429;   // about 15 MB a minute at most
    q << now();
    if (m_incoming.size() > 20 && !m_incoming.contains(id))
        return 429;
    bool ok = false;
    const QByteArray part = open(o, fromKey, &ok);
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
    commitPeer();
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
        Q_EMIT arrived(nameOf(fromKey), voiceMs > 0 ? QStringLiteral("Voice message") : QStringLiteral("Photo"), fromKey);
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

QJsonObject Chat::envelope(const QString &room, const QString &theirKey, const QByteArray &plain, const QString &msgId)
{
    // Only these go in the clear: never a nickname or anything else kept about them here.
    QJsonObject o{{QStringLiteral("room"), room}, {QStringLiteral("fromKey"), myKey()}, {QStringLiteral("fromName"), username()},
                  {QStringLiteral("mailbox"), m_nostr->publicKey()}, {QStringLiteral("id"), msgId}};
    // The invite from their code, until they accept: proves they handed it out within the hour.
    const QString invite = str(m_contacts.value(theirKey), "invite");
    if (!invite.isEmpty())
        o.insert(QStringLiteral("invite"), invite);
    // 0.6: where I'm reached over Tor, for contacts and group peers only (never the Nearby room).
    if (room != QLatin1String("nearby") && (m_contacts.contains(theirKey) || m_gpeers.contains(theirKey)) && !onion().isEmpty())
        o.insert(QStringLiteral("o"), onion());
    return seal(o, theirKey, plain);
}

// r1 (the ratchet) for private-room payloads to a contact whose app said it can; v1 otherwise.
QJsonObject Chat::seal(const QJsonObject &fields, const QString &theirKey, const QByteArray &plain)
{
    // A message from them that just opened moves the same peer: keep that first.
    if (m_pendingPeer && m_pendingKey == theirKey)
        commitPeer();
    const bool r1 = ChatWire::useRatchet(fields.value(QStringLiteral("room")).toString(), isContact(theirKey) || isGroupPeer(theirKey),
                                         m_capable.value(theirKey) || isGroupPeer(theirKey));
    Ratchet::Peer *peer = r1 ? &m_peers[theirKey] : nullptr;
    const QJsonObject o = ChatWire::seal(fields, keyWith(theirKey), theirKey, plain, peer, kNewKey);
    if (peer && ChatWire::isRatchet(o))
        saveRatchet(theirKey);   // saved before it leaves, so a key is never used twice
    return o;
}

void Chat::post(const QString &base, const QByteArray &body, std::function<void(int)> done)
{
    // The same certificate pinning as file transfers: only the device that announced itself.
    QNetworkRequest req = m_net->peerRequest(QUrl(base + kPath));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    req.setTransferTimeout(6000);
    QNetworkReply *r = m_http.post(req, body);
    m_net->pinReply(r);
    connect(r, &QNetworkReply::finished, this, [r, done] {
        r->deleteLater();
        if (done)
            done(r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt());
    });
}

QString Chat::deliver(const QString &key, const QJsonObject &body, const QString &mailbox)
{
    const QByteArray json = compact(body);
    if (m_ble->send(key, json))
        return QStringLiteral("bluetooth");
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty()) {
        post(base, json);
        return QStringLiteral("wifi");
    }
    if (sendInternet(key, {json}, mailbox, false))
        return QStringLiteral("internet");
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
            m_ble->send(k, compact(envelope(QStringLiteral("nearby"), k, t.toUtf8(), id)));
        }
    }
    for (const LocalSend::Peer &p : m_net->chatPeers())
        if (!p.chatName.isEmpty() && !muted.contains(p.chatKey) && !done.contains(p.chatKey)) {
            done.insert(p.chatKey);
            post(p.base, compact(envelope(QStringLiteral("nearby"), p.chatKey, t.toUtf8(), id)));
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
    // Sealed once and kept: every try and every route sends these same bytes.
    const QList<QByteArray> bodies = sealedFor(key, id, m);
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
    for (const QByteArray &b : bodies)
        ble = ble && m_ble->send(key, b);
    if (ble) {
        any = true;
        markSent(QStringLiteral("bluetooth"));
        if (photo)
            t.next = qMax(t.next, now() + bodies.size() * 3000);   // about 3 s a piece over Bluetooth: don't pile up resends
    }
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty()) {
        any = true;
        auto left = std::make_shared<int>(int(bodies.size()));
        auto good = std::make_shared<bool>(true);
        for (const QByteArray &b : bodies)
            post(base, b, [this, key, id, left, good](int code) {
                *good = *good && code == 200;
                if (--*left > 0 || !*good)
                    return;
                // "Sent" only: a 200 just means some device answered. "Delivered" comes from their sealed
                // receipt, which only the real person can make.
                update(key, id, [](QVariantMap &x) {
                    if (str(x, "unsent").isEmpty() && rank(str(x, "state")) < 2) {
                        x.insert(QStringLiteral("state"), QStringLiteral("sent"));
                        x.insert(QStringLiteral("via"), QStringLiteral("wifi"));
                        x.insert(QStringLiteral("failed"), false);
                    }
                });
            });
    }
    const QString mailbox = backupMailbox(m);
    // Big photos only go over the internet if the close routes didn't deliver them within 5 s.
    // Their onion first (Tor); the relays when that fails, and on every try from the second on.
    const bool reachable = !mailbox.isEmpty() || (isContact(key) && !onionOf(key).isEmpty());
    if (reachable && (!photo || !any || t.count >= 2) && bodies.size() <= Photos::kInternetPieces) {
        if (sendInternet(key, bodies, mailbox, t.count >= 2, [markSent] { markSent(QStringLiteral("internet")); }))
            any = true;
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
    for (const QString &k : m_unsentKeeps.keys())
        if (!route(k).isEmpty() && !deliver(k, m_unsentKeeps.value(k), mailboxOf(k)).isEmpty())
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
                dropSealed(id);
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
    groupRetry();
    for (const auto &u : unsends) {
        if (u.second.startsWith(QLatin1Char('!'))) {
            const QString id = u.second.mid(1);
            update(u.first, id, [](QVariantMap &x) { x.insert(QStringLiteral("state"), QStringLiteral("unsent")); });
            m_tries.remove(id);
            dropSealed(QStringLiteral("u-") + id);
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
        if (!m.value(QStringLiteral("mine")).toBool() && !m.value(QStringLiteral("system")).toBool() && str(m, "state") != QLatin1String("read")) {
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
    // Sealed once (kept as "u-<id>"), then the same bytes on every try and route.
    const QString cid = QStringLiteral("u-") + id;
    QByteArray body;
    const ChatWire::Outbox::Entry *e = m_outbox.find(cid);
    if (e && e->key == key && !e->bodies.empty()) {
        body = e->bodies.front();
    } else {
        body = compact(envelope(QStringLiteral("unsend"), key, id.toUtf8(), QUuid::createUuid().toString(QUuid::WithoutBraces)));
        keepSealed(cid, key, {body});
    }
    m_ble->send(key, body);
    const QString base = m_net->baseFor(key);
    if (!base.isEmpty())
        post(base, body);
    sendInternet(key, {body}, mailboxOf(key), t.count >= 2);
}

// ------------------------------------------------------------------ sealed envelopes and ratchet state

QList<QByteArray> Chat::sealedFor(const QString &key, const QString &id, const QVariantMap &m)
{
    const ChatWire::Outbox::Entry *e = m_outbox.find(id);
    if (e && e->key == key && !e->bodies.empty())
        return QList<QByteArray>(e->bodies.begin(), e->bodies.end());
    QList<QByteArray> bodies;
    if (!str(m, "photo").isEmpty()) {
        for (const QJsonObject &o : pieces(m))
            bodies << compact(o);
    } else {
        bodies << compact(envelope(QStringLiteral("private"), key, str(m, "text").toUtf8(), id));
    }
    if (!bodies.isEmpty())
        keepSealed(id, key, bodies);
    return bodies;
}

void Chat::keepSealed(const QString &id, const QString &key, const QList<QByteArray> &bodies)
{
    ChatWire::Outbox::Entry e;
    e.time = now();
    e.key = key;
    e.bodies.assign(bodies.begin(), bodies.end());
    m_outbox.put(id, e);
    // On disk too, so a restart resends the same bytes instead of sealing again.
    if (fileSafe(id))
        m_vault->write(QStringLiteral("outbox/") + id, ChatWire::Outbox::save(e));
}

void Chat::dropSealed(const QString &id)
{
    if (m_outbox.drop(id) && fileSafe(id))
        m_vault->remove(QStringLiteral("outbox/") + id);
}

// Kept envelopes go once their message is delivered, unsent, deleted, or a day old.
void Chat::sweepSealed()
{
    const qint64 t = now();
    for (const QString &id : m_outbox.ids()) {
        const ChatWire::Outbox::Entry *e = m_outbox.find(id);
        bool keep = m_gout.contains(id);   // a group message waiting for that member (groupRetry ends it)
        if (!keep && e && t - e->time <= kDay) {
            const bool unsend = id.startsWith(QLatin1String("u-"));
            const QString msgId = unsend ? id.mid(2) : id;
            for (const QVariant &v : m_private.value(e->key)) {
                const QVariantMap m = v.toMap();
                if (str(m, "id") != msgId || !m.value(QStringLiteral("mine")).toBool())
                    continue;
                keep = unsend ? str(m, "state") == QLatin1String("unsending")
                              : str(m, "unsent").isEmpty() && rank(str(m, "state")) >= 1 && rank(str(m, "state")) <= 2;
            }
        }
        if (!keep)
            dropSealed(id);
    }
}

QString Chat::ratchetFile(const QString &key) const
{
    return QStringLiteral("ratchet/") + QString::fromLatin1(QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha256).toHex().left(32))
        + QStringLiteral(".json");
}

void Chat::loadRatchet()
{
    const QStringList files = QDir(m_vault->path(QStringLiteral("ratchet"))).entryList(QDir::Files);
    for (const QString &f : files) {
        const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QStringLiteral("ratchet/") + f)).object();
        const QString key = o.value(QStringLiteral("key")).toString();
        if (key.isEmpty())
            continue;
        if (o.value(QStringLiteral("cap")).toBool())
            m_capable.insert(key, true);
        if (o.value(QStringLiteral("caps")).isString())
            m_caps.insert(key, o.value(QStringLiteral("caps")).toString().split(QLatin1Char(' '), Qt::SkipEmptyParts));
        if (o.contains(QStringLiteral("peer"))) {
            bool ok = false;
            const Ratchet::Peer p = Ratchet::Peer::fromJson(o.value(QStringLiteral("peer")).toObject(), &ok);
            if (!p.sessions.empty())
                m_peers.insert(key, p);
        }
    }
    const QStringList kept = QDir(m_vault->path(QStringLiteral("outbox"))).entryList(QDir::Files);
    for (const QString &id : kept) {
        ChatWire::Outbox::Entry e;
        if (fileSafe(id) && ChatWire::Outbox::load(m_vault->read(QStringLiteral("outbox/") + id), &e))
            m_outbox.put(id, e);
        else
            m_vault->remove(QStringLiteral("outbox/") + id);
    }
}

void Chat::saveRatchet(const QString &key)
{
    const bool cap = m_capable.value(key);
    const auto p = m_peers.constFind(key);
    if (!cap && !m_caps.contains(key) && (p == m_peers.cend() || p->sessions.empty())) {
        m_vault->remove(ratchetFile(key));
        return;
    }
    QJsonObject o{{QStringLiteral("key"), key}, {QStringLiteral("cap"), cap}};
    if (m_caps.contains(key))
        o.insert(QStringLiteral("caps"), m_caps.value(key).join(QLatin1Char(' ')));
    if (p != m_peers.cend())
        o.insert(QStringLiteral("peer"), p->toJson());
    m_vault->write(ratchetFile(key), compact(o));
}

void Chat::forgetRatchet(const QString &key)
{
    m_peers.remove(key);
    m_capable.remove(key);
    m_caps.remove(key);
    if (m_pendingPeer && m_pendingKey == key)
        m_pendingPeer.reset();
    m_vault->remove(ratchetFile(key));
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
    if (!m_ready)
        return;   // locked: nothing could be saved, so don't pretend
    m_private.clear();
    m_nearby.clear();
    for (Group &gr : m_groups)
        gr.messages.clear();
    m_gidx.clear();
    saveGroupChats();
    QDir(m_vault->path(QStringLiteral("photos"))).removeRecursively();
    m_vault->remove(QStringLiteral("nearby.json"));
    save();
    sweepSealed();
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
    pruneGroups(cutoff);
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
    // Adding a group peer as a contact just makes them a contact (docs/protocol-0.6.md 3.7).
    bool peers = false;
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it)
        if (m_gpeers.remove(it.key()))
            peers = true;
    if (peers)
        saveGroups();
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
        QJsonObject check;
        for (auto it = m_private.begin(); it != m_private.end(); ++it)
            check.insert(it.key(), QJsonArray::fromVariantList(it.value()));
        if (m_vault->write(QStringLiteral("chats.json"), QJsonDocument(check).toJson(QJsonDocument::Compact)))
            QFile::remove(old);   // only once the encrypted copy is saved
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
    loadFolders();
    loadGroups();
    loadRatchet();
    prune();
    sweepSealed();
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
                           {QStringLiteral("unread"), m_unread.contains(k) || shouldAskKeep(k)}, {QStringLiteral("contact"), isContact(k)},
                           {QStringLiteral("folders"), foldersOf(k)}};
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("time")).toLongLong() > b.toMap().value(QStringLiteral("time")).toLongLong();
    });
    return out;
}

QVariantList Chat::requests() const
{
    QVariantList out;
    for (QVariantMap r : m_requests) {
        if (!str(r, "replaces").isEmpty())
            r.insert(QStringLiteral("replacesName"), nameOf(str(r, "replaces")));
        out << r;
    }
    return out;
}

QVariantList Chat::messagesWith(const QString &key) const
{
    // Their messages show the name they have here now (the nickname when there is one).
    QVariantList out = m_private.value(key);
    const QString shown = nameOf(key);
    for (QVariant &v : out) {
        QVariantMap m = v.toMap();
        if (m.value(QStringLiteral("mine")).toBool() || m.value(QStringLiteral("system")).toBool())
            continue;
        m.insert(QStringLiteral("name"), shown);
        if (!str(m, "unsent").isEmpty())
            m.insert(QStringLiteral("unsent"), shown);
        v = m;
    }
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
    // "Nickname (their name)" when there's a nickname.
    auto shown = [this](const QString &key) {
        const QString nick = nicknameOf(key);
        return nick.isEmpty() ? realNameOf(key) : QStringLiteral("%1 (%2)").arg(nick, realNameOf(key));
    };
    for (auto it = m_contacts.begin(); it != m_contacts.end(); ++it) {
        seen.insert(it.key());
        out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), shown(it.key())}, {QStringLiteral("contact"), true},
                           {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
    }
    for (auto it = m_met.begin(); it != m_met.end(); ++it)
        if (!seen.contains(it.key())) {
            seen.insert(it.key());
            out << QVariantMap{{QStringLiteral("key"), it.key()}, {QStringLiteral("name"), shown(it.key())}, {QStringLiteral("contact"), false},
                               {QStringLiteral("verified"), isVerified(it.key())}, {QStringLiteral("blocked"), isBlocked(it.key())}};
        }
    for (const QString &k : m_settings.value(QStringLiteral("chat/muted")).toStringList())
        if (!seen.contains(k))
            out << QVariantMap{{QStringLiteral("key"), k}, {QStringLiteral("name"), shown(k)}, {QStringLiteral("contact"), false},
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
