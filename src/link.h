#pragma once

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

class Chat;

// Linked devices, companion style (docs/protocol-0.6.md section 15), the same as Muninn's Link.kt.
// A companion shows a phone's 1:1 chats and sends through it while the phone is online; contacts see
// nothing new. Huginn can be either end:
//  - a companion: it shows a link code (MUNINNL1:, its chat key, mailbox and a token good for 10
//    minutes and once); the phone takes it and answers t "hello". Then "chat" and "msg" mirror the
//    phone's chats here, and "send" asks the phone to send as itself.
//  - the phone: it takes a companion's code (Link a device) and mirrors its own 1:1 chats to it.
// Room "link" is sealed like a private message and goes on every route; Chat hands it here
// (Chat::receiveExtra), and anything from a device that isn't linked is answered 403.
class Link : public QObject
{
    Q_OBJECT
    // On a companion: the phone it mirrors, {key, name}, or empty.
    Q_PROPERTY(QVariantMap phone READ phone NOTIFY changed)
    // On the phone: the devices that mirror it, [{key, name}].
    Q_PROPERTY(QVariantList companions READ companions NOTIFY changed)
    // On a companion: the phone's chats, newest first: [{peer, name, last, time}].
    Q_PROPERTY(QVariantList mirror READ mirror NOTIFY changed)
    // The link code being shown here (empty until newCode), and when it stops working (ms).
    Q_PROPERTY(QString code READ code NOTIFY changed)
    Q_PROPERTY(qint64 codeUntil READ codeUntil NOTIFY changed)

public:
    static const char kPrefix[];            // "MUNINNL1:"
    static constexpr qint64 kTokenMs = 10 * 60000;
    static constexpr int kChatMsgs = 25;

    explicit Link(Chat *chat, QObject *parent = nullptr);

    QVariantMap phone() const;
    QVariantList companions() const;
    QVariantList mirror() const;
    QString code() const { return m_code; }
    qint64 codeUntil() const { return m_tokenUntil; }

    // A new link code for a phone to take (a new token each time).
    Q_INVOKABLE QString newCode();
    // On the phone: link the device whose code this is. "" when done, else what to tell the person.
    Q_INVOKABLE QString link(const QString &code);
    Q_INVOKABLE void unlink(const QString &key);
    // On a companion: one mirrored chat's messages, oldest first: [{id, text, mine, time}].
    Q_INVOKABLE QVariantList messages(const QString &peer) const;
    // On a companion: send through the phone. False when no phone is linked.
    Q_INVOKABLE bool send(const QString &peer, const QString &text);
    Q_INVOKABLE void sync();

    // Room "link" from fromKey, opened. False: not from a device linked with this one.
    bool receive(const QString &fromKey, const QJsonObject &p);
    // On the phone: a message in or out of a 1:1 chat (Chat::addPrivate), for the companions.
    void onMessage(const QString &peer, const QVariantMap &m);
    // On a companion: messages past each chat's limit (cap d1, the phone's "d") go, as on the phone.
    void prune(qint64 now);

    // A link code's {k, p, n, i}; empty when it isn't one.
    static QJsonObject readCode(const QString &text);
    static bool selfTest(const QJsonObject &vectors);

Q_SIGNALS:
    void changed();

private:
    struct Device {
        QString key, mailbox, name;
    };
    struct M {
        QString id, text;
        bool mine = false;
        qint64 time = 0;
    };
    struct Mirror {
        QString peer, name;
        QList<M> msgs;
        int days = 7;                       // the phone's limit for this chat (Limits::kDefault)
    };
    void ensureLoaded() const;
    void save();
    void saveMirror();
    void sendTo(const Device &d, const QJsonObject &msg);
    void syncAll(const Device *only);
    QJsonObject mJson(const QVariantMap &m, int max) const;
    static M mOf(const QJsonObject &o);
    static int daysOf(const QJsonObject &o, int fallback);

    Chat *m_chat;
    mutable bool m_loaded = false;
    mutable QList<Device> m_companions;
    mutable Device m_phone;                 // key empty: none
    mutable QHash<QString, Mirror> m_mirror;
    QString m_token, m_code;
    qint64 m_tokenUntil = 0;
    // Ids of companions' "send" already sent (two days, on disk), so none goes out twice.
    bool firstSend(const QString &id);
    QHash<QString, qint64> m_sends;
    bool m_sendsLoaded = false;
};
