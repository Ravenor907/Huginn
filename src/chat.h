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
//  - Groups (0.6, chatgroups.cpp): a signed state per group, every message sealed separately to
//    each member over that member's own session. See docs/protocol-0.6.md and GroupWire.
//  - Contact folders: local only, never sent.
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
    Q_PROPERTY(QVariantList groups READ groups NOTIFY changed)
    Q_PROPERTY(QVariantList groupInvites READ groupInvites NOTIFY changed)
    Q_PROPERTY(QVariantList folders READ folders NOTIFY changed)
    Q_PROPERTY(QString netStatus READ netStatus NOTIFY changed)

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
    QString netStatus() const;

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

    // What their app can do (the "caps" of their envelopes). Unknown until they send something
    // from 0.6 or answer probeCaps (at most once an hour each).
    Q_INVOKABLE bool capsKnown(const QString &key) const { return m_caps.contains(key); }
    Q_INVOKABLE bool canGroup(const QString &key) const;
    Q_INVOKABLE void probeCaps(const QString &key);

    // Calls (docs/protocol-0.6.md 5.2), for CallSignalHooks in main.cpp. sendCall seals msg as
    // room "call" and sends it on every route at once, never through the Outbox.
    void sendCall(const QString &key, const QJsonObject &msg);
    QByteArray signWithChatKey(const QByteArray &bytes) const;
    bool canCall(const QString &key) const;     // contact or group peer, caps c1, not blocked
    QString lanHost(const QString &key) const;  // the host of their Wi-Fi route, or ""

    // Contact folders, only on this computer: [{id, name, count}].
    QVariantList folders() const;
    Q_INVOKABLE QString addFolder(const QString &name);
    Q_INVOKABLE void renameFolder(const QString &id, const QString &name);
    Q_INVOKABLE void deleteFolder(const QString &id);
    Q_INVOKABLE void setInFolder(const QString &id, const QString &key, bool on);
    Q_INVOKABLE QStringList foldersOf(const QString &key) const;

    // Groups (chatgroups.cpp). Calls that can fail return what to tell the person, "" when done.
    QVariantList groups() const;
    QVariantList groupInvites() const;
    Q_INVOKABLE QString createGroup(const QString &name);
    Q_INVOKABLE QVariantMap groupInfo(const QString &g) const;
    Q_INVOKABLE QVariantList groupMessages(const QString &g, const QString &ch) const;
    Q_INVOKABLE void sendGroup(const QString &g, const QString &ch, const QString &text);
    Q_INVOKABLE bool sendGroupPhoto(const QString &g, const QString &ch, const QString &path, bool hd);
    Q_INVOKABLE void markGroupRead(const QString &g, const QString &ch);
    Q_INVOKABLE void unsendGroup(const QString &g, const QString &id);
    Q_INVOKABLE void deleteGroupMessage(const QString &g, const QString &id);
    Q_INVOKABLE void retryGroup(const QString &g, const QString &id);
    Q_INVOKABLE QString inviteToGroup(const QString &g, const QString &key);
    Q_INVOKABLE QString groupCode(const QString &g);              // a code good for an hour, or ""
    Q_INVOKABLE void copyText(const QString &text) const;
    Q_INVOKABLE QVariantMap parseGroupCode(const QString &text) const;
    Q_INVOKABLE QString joinWithCode(const QString &text);
    Q_INVOKABLE QString acceptGroupInvite(const QString &g);
    Q_INVOKABLE void declineGroupInvite(const QString &g);
    Q_INVOKABLE QString renameGroup(const QString &g, const QString &name);
    Q_INVOKABLE QString addChannel(const QString &g, const QString &name, bool voice);
    Q_INVOKABLE QString renameChannel(const QString &g, const QString &ch, const QString &name);
    Q_INVOKABLE QString removeChannel(const QString &g, const QString &ch);
    Q_INVOKABLE QString setAdmin(const QString &g, const QString &key, bool on);
    Q_INVOKABLE QString removeMember(const QString &g, const QString &key);
    Q_INVOKABLE QString handOver(const QString &g, const QString &key);
    Q_INVOKABLE QString leaveGroup(const QString &g);
    Q_INVOKABLE void forgetGroup(const QString &g);

    static const int kMax = 2000;
    static QString code(const QString &name, const QString &chatKey, const QString &mailbox, const QString &invite = QString(), qint64 expires = 0);

Q_SIGNALS:
    void changed();
    void notice(const QString &text);
    // A message arrived: for the toast, sound and desktop notification. For a group message the
    // key is "g:<group>:<channel>".
    void arrived(const QString &name, const QString &text, const QString &key);
    // An authentic room "call" envelope from a contact or group peer (at most 30 in 10 s each).
    void callMessage(const QString &fromKey, const QJsonObject &msg);

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
    bool isGroupPeer(const QString &key) const { return m_gpeers.contains(key); }
    // Sends over the internet: to their mailbox on the relays. False when they have none.
    bool sendInternet(const QString &key, const QList<QByteArray> &bodies, const QString &mailbox,
                      std::function<void()> sent = nullptr);
    void noteCaps(const QString &key, bool present, const QStringList &caps);
    void loadFolders();
    void saveFolders();

    // ---- groups (chatgroups.cpp)
    struct Group {
        QJsonObject state;                  // the current accepted state
        QList<QJsonObject> history;         // the last accepted states, oldest first
        QVariantList messages;              // every channel's, each with "ch"
        QHash<QString, int> unread;         // channel -> unread messages
        QList<QJsonObject> held;            // messages from someone not in the state yet
        QJsonObject lastOp;                 // my last change, re-done if it loses a conflict
        bool removed = false, left = false;
        qint64 since = 0;
    };
    struct GroupOut {                       // one member's copy of a group message, until they confirm
        QString g, to, logical, room;
        qint64 time = 0, next = 0;
        int count = 0;
    };
    int receiveGroup(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via);
    int receiveGroupPiece(const QJsonObject &o, const QString &fromKey, const QString &name, const QString &mailbox, const QString &via);
    bool applyState(const QString &g, const QJsonObject &next, const QString &from, const QString &op);
    void stateAccepted(const QString &g, const QJsonObject &old, bool first);
    QString change(const QString &g, const QJsonObject &op);
    QJsonObject nextState(const QString &g) const;
    QString commit(const QString &g, QJsonObject next, const QJsonObject &op, const QStringList &alsoTo = QStringList());
    void queueGroup(const QString &g, const QString &to, const QString &room, const QString &logical, const QByteArray &plain);
    void queueGroupBodies(const QString &g, const QString &to, const QString &room, const QString &logical, const QList<QByteArray> &bodies);
    void sendControl(const QString &g, const QString &to, QJsonObject msg);
    void groupReceipt(const QString &g, const QString &to, const QStringList &ids, const QString &state);
    void groupAttempt(const QString &envId);
    void groupRetry();
    void sendSync(const QString &g, const QString &to);
    void sendStateTo(const QString &g, const QString &to);
    void refreshGroupPeers();
    void storeGroupMessage(const QString &g, const QVariantMap &m, bool notify);
    bool gmsgKnown(const QString &id) const { return m_gidx.contains(id) || m_deleted.contains(id); }
    void processHeld(const QString &g);
    void groupNote(const QString &g, const QString &text);
    QString groupName(const QString &g) const;
    QString memberName(const QString &g, const QString &key) const;
    QString sendGroupBlob(const QString &g, const QString &ch, const QByteArray &bytes, int voiceMs);
    QList<QByteArray> groupPieces(const QString &g, const QString &ch, const QString &gid, const QString &to, const QByteArray &bytes, int voiceMs);
    void updateGroupMessage(const QString &g, const QString &id, const std::function<void(QVariantMap &)> &f);
    bool tokenOk(const QString &token, const QString &g) const;
    int gmsgIn(const QString &g, const QString &from, const QString &name, const QString &via, const QJsonObject &p);
    void loadGroups();
    void saveGroups();
    void saveGroupsSoon();
    void saveGroupChats();
    void pruneGroups(qint64 cutoff);

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
    // Who the message being handled proved to be from, whether it said "cap":"r1", and its "caps".
    QString m_openedFrom;
    bool m_openedCap = false, m_openedHasCaps = false;
    QStringList m_openedCaps;
    QHash<QString, QStringList> m_caps;                 // key -> their caps tokens (known only)
    QHash<QString, qint64> m_probed;                    // key -> last caps probe
    QList<QVariantMap> m_folders;                       // {id, name, keys}
    QHash<QString, Group> m_groups;
    QHash<QString, QString> m_gidx;                     // group message id -> group
    QHash<QString, QVariantMap> m_gpeers;               // members who aren't contacts: key -> {key, name, mailbox}
    QHash<QString, QVariantMap> m_ginvites;             // invites from contacts: group -> {g, from, fromName, name, count, inv}
    QHash<QString, QVariantMap> m_joining;              // groups being joined: group -> {admin, adminName, mailbox, token, name, since}
    QHash<QString, QVariantMap> m_gtokens;              // my invites and group codes: token -> {g, x, for}
    QHash<QString, GroupOut> m_gout;                    // envelope id -> waiting for that member's receipt
    QHash<QString, qint64> m_syncAsked;                 // group|key -> when
    bool m_groupsDirty = false;
    QHash<QString, QList<qint64>> m_recent, m_pieceTimes, m_recentGroup, m_recentCall;
    struct Tries { int count = 0; qint64 next = 0; };
    QHash<QString, Tries> m_tries;
    struct Incoming { QString from; QList<QByteArray> parts; int have = 0; qint64 started = 0; };
    QHash<QString, Incoming> m_incoming;
};
