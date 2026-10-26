#pragma once

#include "chatwire.h"
#include "ratchet.h"

#include <QHash>
#include <QJsonObject>
#include <functional>
#include <optional>
#include <QNetworkAccessManager>
#include <QObject>
#include <QSet>
#include <QSettings>
#include <QTimer>
#include <QVariantList>

class Ble;
class LocalSend;
class Nostr;
class Vault;

// Huginn's messaging, the same as Muninn's (net/Chat.kt) so the two work together:
//  - Three ways to reach someone, all tried at once: Bluetooth (a Muninn phone close by),
//    the same Wi-Fi (the LocalSend port), and the internet (Nostr relays, for contacts).
//    The other side keeps the first copy; anything not "delivered" is sent again for a day.
//  - Rooms: Nearby (public, everyone close by or on the Wi-Fi) and Private (one person).
//    Every message is sealed end to end with the chat keys whichever way it travels.
//  - People close by can message straight away. After 10 messages, or once they're out of
//    range, both get "Keep in touch?"; when both say yes they become contacts (internet too).
//  - Receipts (Sending, Sent, Delivered, Read), unsend, photos and voice messages in pieces.
//  - Contacts whose app says "cap":"r1" get the message ratchet (forward secrecy) for private
//    messages; everyone else, and the Nearby room, stays on the pair key (v1). See ChatWire.
//  - Everything kept is encrypted in the Vault.
class Chat : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    Q_PROPERTY(QVariantList nearby READ nearby NOTIFY changed)
    Q_PROPERTY(QVariantList chats READ chats NOTIFY changed)
    Q_PROPERTY(QVariantList requests READ requests NOTIFY changed)
    Q_PROPERTY(QVariantList around READ around NOTIFY changed)
    Q_PROPERTY(QVariantList people READ people NOTIFY changed)
    Q_PROPERTY(int nearbyCount READ nearbyCount NOTIFY changed)
    Q_PROPERTY(int unreadCount READ unreadCount NOTIFY changed)
    Q_PROPERTY(QString username READ username WRITE setUsername NOTIFY changed)
    Q_PROPERTY(bool nearbyOn READ nearbyOn WRITE setNearbyOn NOTIFY changed)
    Q_PROPERTY(bool keepNearby READ keepNearby WRITE setKeepNearby NOTIFY changed)
    Q_PROPERTY(bool readReceipts READ readReceipts WRITE setReadReceipts NOTIFY changed)
    Q_PROPERTY(bool showPreview READ showPreview WRITE setShowPreview NOTIFY changed)
    Q_PROPERTY(qint64 deleteAfter READ deleteAfter WRITE setDeleteAfter NOTIFY changed)
    Q_PROPERTY(int internet READ internet NOTIFY changed)
    Q_PROPERTY(QString myLink READ myLink NOTIFY changed)          // the code being shown (newLink makes one)
    Q_PROPERTY(qint64 linkExpires READ linkExpires NOTIFY changed)
    Q_PROPERTY(QString openChat MEMBER m_openChat NOTIFY changed)

public:
    Chat(LocalSend *net, Vault *vault, Nostr *nostr, Ble *ble, QObject *parent = nullptr);

    bool ready() const { return m_ready; }
    QVariantList nearby() const { return m_nearby; }
    QVariantList chats() const;
    QVariantList requests() const;
    QVariantList around() const;
    QVariantList people() const;
    int nearbyCount() const;
    int unreadCount() const { return int(m_unread.size()); }
    QString username() const;
    void setUsername(const QString &v);
    bool nearbyOn() const { return m_settings.value(QStringLiteral("chat/nearbyOn"), true).toBool(); }
    void setNearbyOn(bool v);
    bool keepNearby() const { return m_settings.value(QStringLiteral("chat/keepNearby"), false).toBool(); }
    void setKeepNearby(bool v);
    bool readReceipts() const { return m_settings.value(QStringLiteral("chat/readReceipts"), true).toBool(); }
    void setReadReceipts(bool v) { m_settings.setValue(QStringLiteral("chat/readReceipts"), v); Q_EMIT changed(); }
    bool showPreview() const { return m_settings.value(QStringLiteral("chat/showPreview"), false).toBool(); }
    void setShowPreview(bool v) { m_settings.setValue(QStringLiteral("chat/showPreview"), v); Q_EMIT changed(); }
    qint64 deleteAfter() const { return m_settings.value(QStringLiteral("chat/deleteAfter"), 0).toLongLong(); }
    void setDeleteAfter(qint64 v) { m_settings.setValue(QStringLiteral("chat/deleteAfter"), v); prune(); Q_EMIT changed(); }
    int internet() const;
    QString myLink() const { return m_link; }
    qint64 linkExpires() const { return m_linkExpires; }
    QString chatKey() const { return myKey(); }
    QString mailbox() const;
    // A new code with an invite good for an hour: a leaked code stops working after that.
    Q_INVOKABLE QString newLink();

    // Messages
    Q_INVOKABLE QVariantList messagesWith(const QString &key) const;
    Q_INVOKABLE void sendNearby(const QString &text);
    Q_INVOKABLE void sendPrivate(const QString &key, const QString &text);
    Q_INVOKABLE void markRead(const QString &key);
    Q_INVOKABLE void deleteMessage(const QString &key, const QString &id);
    Q_INVOKABLE void deleteNearbyMessage(const QString &id);
    Q_INVOKABLE void unsend(const QString &key, const QString &id);
    Q_INVOKABLE void retry(const QString &key, const QString &id);
    Q_INVOKABLE void deleteAll();
    // Photos (any picture file: shrunk, metadata removed) and voice messages
    Q_INVOKABLE bool sendPhoto(const QString &key, const QString &path, bool hd);
    Q_INVOKABLE void sendVoice(const QString &key, const QByteArray &aac, int ms);
    Q_INVOKABLE QByteArray blob(const QString &id) const;
    Q_INVOKABLE QString photoUrl(const QString &id);
    Q_INVOKABLE bool savePhoto(const QString &id);
    Q_INVOKABLE QVariantList recentPictures() const;   // Steam screenshots and Pictures, newest first
    Q_INVOKABLE void copyLink() const;

    // People
    Q_INVOKABLE QString nameOf(const QString &key) const;          // the nickname when there is one
    Q_INVOKABLE QString realNameOf(const QString &key) const;      // the name they gave themselves
    Q_INVOKABLE QString nicknameOf(const QString &key) const;
    // Only on this computer, never sent. Empty goes back to their own name.
    Q_INVOKABLE void setNickname(const QString &key, const QString &nick);
    // An existing contact a code may be a new security key for, so the screen can ask before
    // replacing it: {key, name, sameInbox} (sameInbox: their inbox, so it can only be them or
    // someone pretending), or empty when there's none.
    Q_INVOKABLE QVariantMap replaces(const QString &text) const;
    Q_INVOKABLE bool keyChanged(const QString &key) const;
    Q_INVOKABLE QString route(const QString &key) const;
    Q_INVOKABLE bool isContact(const QString &key) const { return m_contacts.contains(key); }
    Q_INVOKABLE bool isVerified(const QString &key) const;
    Q_INVOKABLE QString codeWith(const QString &key) const;
    Q_INVOKABLE void verify(const QString &key);
    Q_INVOKABLE void meet(const QString &key, const QString &name);
    Q_INVOKABLE bool shouldAskKeep(const QString &key) const;
    Q_INVOKABLE bool saidKeep(const QString &key) const;
    Q_INVOKABLE bool theySaidKeep(const QString &key) const;
    Q_INVOKABLE void keepInTouch(const QString &key);
    Q_INVOKABLE void notNow(const QString &key);
    Q_INVOKABLE QVariantMap parseCode(const QString &text) const;   // {name, key} or empty
    // sameAs: the contact whose security key this code replaces (after asking), or empty.
    Q_INVOKABLE void addContact(const QString &text, const QString &sameAs = QString());
    Q_INVOKABLE void acceptRequest(const QString &key);
    Q_INVOKABLE void declineRequest(const QString &key);
    Q_INVOKABLE void block(const QString &key, bool on = true);
    Q_INVOKABLE bool isBlocked(const QString &key) const;
    Q_INVOKABLE void removeContact(const QString &key);

    static const int kMax = 2000;
    static QString code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite = QString(), qint64 expires = 0);

Q_SIGNALS:
    void changed();
    void notice(const QString &text);
    // A private message arrived: for the toast, sound and desktop notification.
    void arrived(const QString &name, const QString &text, const QString &key);

private:
    void start();
    int receive(const QByteArray &body, const QString &via);
    int receiveEnvelope(const QJsonObject &o, const QString &via);
    int receivePiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &via);
    QJsonObject envelope(const QString &room, const QString &theirKey, const QByteArray &plain, const QString &msgId);
    QJsonObject seal(const QJsonObject &fields, const QString &theirKey, const QByteArray &plain);
    QByteArray open(const QJsonObject &o, const QString &fromKey, bool *ok);
    void commitPeer();
    QString deliver(const QString &key, const QJsonObject &body, const QString &mailbox);
    void post(const QString &base, const QByteArray &body, std::function<void(int)> done = nullptr);
    void attempt(const QString &key, const QString &id);
    void attemptUnsend(const QString &key, const QString &id);
    void retryLoop();
    void receipt(const QString &key, const QStringList &ids, const QString &state, const QString &mailbox);
    QString backupMailbox(const QVariantMap &m) const;
    QVariantMap message(const QString &id, const QString &peer, const QString &name, const QString &text, bool mine, const QString &via) const;
    bool addPrivate(const QString &peer, const QVariantMap &m);
    void update(const QString &peer, const QString &id, const std::function<void(QVariantMap &)> &f);
    bool known(const QString &key, const QString &id) const;
    void forget(const QStringList &ids);
    void addNearby(const QVariantMap &m);
    void becomeContacts(const QString &key);
    void putMet(const QString &key, const QVariantMap &m);
    QString sendBlob(const QString &key, const QByteArray &bytes, int voiceMs);
    QList<QJsonObject> pieces(const QVariantMap &m);
    QList<QByteArray> sealedFor(const QString &key, const QString &id, const QVariantMap &m);
    void keepSealed(const QString &id, const QString &key, const QList<QByteArray> &bodies);
    void dropSealed(const QString &id);
    void sweepSealed();
    // Ratchet state per person, in the vault (ratchet/<hash>.json).
    void loadRatchet();
    void saveRatchet(const QString &key);
    void forgetRatchet(const QString &key);
    QString ratchetFile(const QString &key) const;
    void noteCapable(const QString &key, bool capable);
    // An existing contact's security key is being replaced: keep them, but unverified and warned.
    void replaceKey(const QString &oldKey, const QString &newKey, const QString &name, const QString &mailbox);
    QString contactWithMailbox(const QString &mailbox, const QString &notKey) const;
    void systemNote(const QString &key, const QString &text);
    void prune();
    void save();
    void saveMet();
    void saveContacts();
    void load();
    QByteArray keyWith(const QString &theirKey) const;
    QString myKey() const { return QString::fromLatin1(m_pub.toBase64()); }
    QString wrap(const QString &peerKey, const QByteArray &body) const;
    QByteArray unwrap(const QString &content) const;
    bool inviteOk(const QString &token) const;
    void saveInvites();
    QString mailboxOf(const QString &key) const;

    LocalSend *m_net;
    Vault *m_vault;
    Nostr *m_nostr;
    Ble *m_ble;
    QSettings m_settings;
    QNetworkAccessManager m_http;
    QTimer m_retry, m_prune;
    bool m_ready = false;
    QString m_openChat;
    QByteArray m_priv, m_pub;
    QVariantList m_nearby;
    QHash<QString, QVariantList> m_private;
    QHash<QString, QVariantMap> m_contacts;            // key -> {key, name, mailbox, verified}
    QHash<QString, QVariantMap> m_met;                 // key -> {name, mailbox, iSaid, theySaid, dismissed, verified}
    QHash<QString, QVariantMap> m_held;                // key -> {id -> message}, waiting on a request
    QHash<QString, QVariantMap> m_requests;            // key -> {key, name, code, mailbox}
    QHash<QString, qint64> m_deleted;
    QHash<QString, qint64> m_invites;                  // invites in my codes: token -> expiry ms
    QString m_link;
    qint64 m_linkExpires = 0;                  // ids deleted here (two days): late copies stay gone
    QSet<QString> m_unread;
    QHash<QString, QJsonObject> m_unsentKeeps;          // key -> the sealed "keep" not delivered yet
    QHash<QString, bool> m_capable;                     // key -> their app said "cap":"r1"
    QHash<QString, Ratchet::Peer> m_peers;
    ChatWire::Outbox m_outbox;                          // sealed envelopes for resending, per message id
    ChatWire::Seen m_seen;
    // The sender's peer after a ratchet message opened, kept once the message is accepted.
    QString m_pendingKey;
    std::optional<Ratchet::Peer> m_pendingPeer;
    // Who the message being handled proved to be from, and whether it said "cap":"r1".
    QString m_openedFrom;
    bool m_openedCap = false;
    QHash<QString, QList<qint64>> m_recent, m_pieceTimes;
    struct Tries { int count = 0; qint64 next = 0; };
    QHash<QString, Tries> m_tries;
    struct Incoming { QString from; QList<QByteArray> parts; int have = 0; qint64 started = 0; };
    QHash<QString, Incoming> m_incoming;
};
