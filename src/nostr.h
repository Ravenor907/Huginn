#pragma once

#include <functional>

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QStringList>

class QWebSocket;
class Tor;

// Messages for people who aren't nearby, through public Nostr relays (no accounts, no server to
// run). Each device has a Nostr key; its public half is its mailbox. A message is an event of
// kind 1059 addressed to the mailbox, signed by a throwaway key so relays can't tell who sent it.
// The content is already sealed with the chat keys (ChatCrypto), so relays only see scrambled text.
// Same behaviour and wire format as Muninn's Nostr.kt.
// Relays are only ever reached through Tor (setTor): no Tor, or Tor not ready yet, means no
// connection at all, never a direct one. Each relay gets circuits of its own.
class Nostr : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString publicKey READ publicKey NOTIFY publicKeyChanged)
    Q_PROPERTY(bool keepAlive READ keepAlive WRITE setKeepAlive NOTIFY keepAliveChanged)

public:
    static constexpr int Kind = 1059;
    static QStringList defaultRelays();

    // secret gives this device's 32-byte Nostr secret (64 hex characters also work); empty while locked.
    explicit Nostr(std::function<QByteArray()> secret, QObject *parent = nullptr);
    ~Nostr() override;

    // Every envelope that arrives for this device (content of the event). Return false to see it again later.
    std::function<bool(const QString &content)> onEnvelope;

    int connected() const { return int(m_live.size()); }
    // The mailbox (x-only public key in hex). Cached, so it still works while locked; empty if never unlocked.
    QString publicKey() const;
    // QSettings nostr/relays (one wss:// address per line) when set, otherwise defaultRelays().
    QStringList relays() const;
    // Reconnect by itself when a relay drops (set while Huginn is listening for messages).
    bool keepAlive() const { return m_keepAlive; }
    void setKeepAlive(bool on);
    // The Tor every relay connection goes through. Connections wait until it is ready.
    void setTor(Tor *tor);

    Q_INVOKABLE void connectRelays();
    // The network came back: drop dead connections and connect again now.
    Q_INVOKABLE void reconnectNow();
    // Asks every open relay for the inbox again: after unlocking (the key and the handler change),
    // or once the mailbox is first known on a fresh install.
    Q_INVOKABLE void resubscribe();
    // Closes every relay and turns keepAlive off. Hides QObject::disconnect; call that one as QObject::disconnect.
    Q_INVOKABLE void disconnect();
    // Sends sealed content to someone's mailbox through every relay. Returns how many relays are connected now.
    Q_INVOKABLE int publish(const QString &mailboxHex, const QString &content);

    // NIP-01: the event id is the SHA-256 of [0,pubkey,created_at,kind,tags,content] as compact JSON.
    static QByteArray serialize(const QString &pubkey, qint64 createdAt, int kind, const QJsonArray &tags, const QString &content);
    static QString idOf(const QJsonObject &ev);
    // A signed event. createdAt < 0 means now; empty aux means random.
    static QJsonObject event(int kind, const QJsonArray &tags, const QString &content, const QByteArray &secret,
                             qint64 createdAt = -1, const QByteArray &aux = QByteArray());
    // Checks tests/nostr-vectors.json; prints what it found. Returns true when everything matches.
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void connectedChanged();
    void publicKeyChanged();
    void keepAliveChanged();

private:
    struct Link {
        QPointer<QWebSocket> ws;
        bool ponged = true;
    };

    QByteArray secret() const;
    void open(const QString &url);
    void opened(const QString &url, QWebSocket *ws);
    void gone(const QString &url, QWebSocket *ws);
    void drop(QWebSocket *ws);
    void subscribe(QWebSocket *ws);
    void onMessage(const QString &url, QWebSocket *ws, const QString &text);
    void handle(const QJsonObject &ev);
    void setLive(const QString &url, bool live);

    QPointer<Tor> m_tor;
    bool m_wanted = false;            // asked to connect while Tor wasn't ready
    std::function<QByteArray()> m_secret;
    mutable QString m_pub;
    QString m_subId;
    bool m_keepAlive = false;
    QHash<QString, Link> m_links;     // exactly one socket per relay
    QSet<QString> m_live;             // relays whose socket is open
    QHash<QString, int> m_retries;
    QStringList m_outbox;             // events waiting for a relay
    QStringList m_seen;               // oldest first, at most 2000
    QSet<QString> m_seenSet;
    QSet<QString> m_working;
    QSet<QString> m_announced;        // announced while locked, not opened yet
};
