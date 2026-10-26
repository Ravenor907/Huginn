#pragma once

#include <QHash>
#include <QNetworkAccessManager>
#include <QObject>
#include <QSettings>
#include <QVariantList>

class LocalSend;

// Texting between Huginn and Muninn, on the LocalSend port (POST /api/huginn/v1/message).
//  - Nearby: a public room for everyone on the same network with either app. Usernames only,
//    no codes. Each copy is sealed for its recipient, so other devices on the Wi-Fi can't read it,
//    but everyone in the room can.
//  - Private: one person to another, verified once by both screens showing the same 6-digit code.
class Chat : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList nearby READ nearby NOTIFY changed)
    Q_PROPERTY(QVariantList conversations READ conversations NOTIFY changed)
    Q_PROPERTY(QVariantList requests READ requests NOTIFY changed)
    Q_PROPERTY(QVariantList people READ people NOTIFY changed)
    Q_PROPERTY(int nearbyCount READ nearbyCount NOTIFY changed)
    Q_PROPERTY(QString username READ username WRITE setUsername NOTIFY changed)
    Q_PROPERTY(bool nearbyOn READ nearbyOn WRITE setNearbyOn NOTIFY changed)
    Q_PROPERTY(bool keepNearby READ keepNearby WRITE setKeepNearby NOTIFY changed)

public:
    explicit Chat(LocalSend *net, QObject *parent = nullptr);

    QVariantList nearby() const { return m_nearby; }
    QVariantList conversations() const;
    QVariantList requests() const;
    QVariantList people() const;
    int nearbyCount() const;
    QString username() const;
    void setUsername(const QString &v);
    bool nearbyOn() const { return m_settings.value(QStringLiteral("chat/nearbyOn"), true).toBool(); }
    void setNearbyOn(bool v);
    bool keepNearby() const { return m_settings.value(QStringLiteral("chat/keepNearby"), false).toBool(); }
    void setKeepNearby(bool v);

    Q_INVOKABLE void sendNearby(const QString &text);
    Q_INVOKABLE void sendPrivate(const QString &key, const QString &name, const QString &text);
    Q_INVOKABLE QVariantList messagesWith(const QString &key) const { return m_private.value(key); }
    Q_INVOKABLE QString codeWith(const QString &key) const;
    Q_INVOKABLE bool isTrusted(const QString &key) const;
    Q_INVOKABLE void acceptPair(const QString &key);
    Q_INVOKABLE void declinePair(const QString &key);
    Q_INVOKABLE void mute(const QString &key);
    Q_INVOKABLE void unmuteAll();
    Q_INVOKABLE int mutedCount() const;

    static const int kMax = 2000;

Q_SIGNALS:
    void changed();
    void notice(const QString &text);

private:
    int receive(const QByteArray &body, const QString &ip);
    void deliver(const QString &base, const QString &theirKey, const QString &room, const QString &text, const QString &peerKey, const QString &msgId);
    QVariantMap message(const QString &peer, const QString &name, const QString &text, bool mine) const;
    void addNearby(const QVariantMap &m);
    void addPrivate(const QString &peer, const QVariantMap &m);
    void markFailed(const QString &peer, const QString &id);
    void save() const;
    void load();
    QByteArray keyWith(const QString &theirKey) const;

    LocalSend *m_net;
    QSettings m_settings;
    QNetworkAccessManager m_http;
    QByteArray m_priv, m_pub;
    QVariantList m_nearby;
    QHash<QString, QVariantList> m_private;
    QHash<QString, QVariantList> m_held;               // private messages waiting on a code
    QHash<QString, QVariantMap> m_requests;            // key -> {key, name, code}
    QHash<QString, QList<qint64>> m_recent;            // rate limit per sender
};
