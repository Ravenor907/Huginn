#pragma once

#include <functional>

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QObject>
#include <QPointer>
#include <QProcess>
#include <QQueue>
#include <QStringList>
#include <QTcpServer>
#include <QTimer>

class QTcpSocket;
class Vault;

// Every internet connection Huginn makes goes through Tor, which ships inside the app and runs as
// a child process. Nothing falls back to a direct connection: until Tor is ready, internet traffic
// simply waits. Nearby Wi-Fi and Bluetooth stay direct.
//
// Tor gets a private data folder, a SOCKS port and a control port picked by itself (both on
// 127.0.0.1 only), and cookie authentication. This device also runs an onion service: virtual
// port 80 maps to a small HTTP listener on 127.0.0.1 that accepts POST /api/huginn/v1/message,
// the same endpoint nearby Wi-Fi uses. Its private key is kept in the Vault (tor-onion.key), so the
// address stays the same across restarts; while the Vault is locked Tor runs without the service
// and adds it the moment the Vault opens.
class Tor : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString onion READ onion NOTIFY onionChanged)
    Q_PROPERTY(bool onionPublished READ onionPublished NOTIFY onionChanged)

public:
    static constexpr const char *MessagePath = "/api/huginn/v1/message";
    static constexpr qint64 MaxBody = 8 * 1024 * 1024;   // same cap as the Wi-Fi endpoint
    static constexpr int MaxHead = 64 * 1024;

    // vault keeps the onion key; nullptr gives a new throwaway address on every start (self-test).
    // dataDir empty means <app data>/tor.
    explicit Tor(Vault *vault, QObject *parent = nullptr, const QString &dataDir = QString());
    ~Tor() override;

    // The one created in main.cpp (the last one constructed).
    static Tor *instance();
    // Where the tor program is: /app/bin/tor in the Flatpak, tor.exe next to huginn.exe on Windows.
    static QString binary();

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();

    int progress() const { return m_progress; }
    bool ready() const { return m_ready; }
    QString state() const { return m_state; }

    // SOCKS5 on 127.0.0.1:<tor's port>, host names resolved by Tor. While Tor isn't ready the port
    // is 0, so anything that uses it anyway fails instead of going out directly.
    QNetworkProxy proxy() const { return proxy(QString()); }
    // The same, with a SOCKS user name: Tor keeps each different name on circuits of its own, so
    // (for example) every Nostr relay sees a different exit and can't link the connections.
    QNetworkProxy proxy(const QString &isolation) const;

    // This device's v3 onion address without ".onion"; empty until the Vault has been opened once.
    QString onion() const { return m_onion; }
    // Tor has uploaded the service descriptor at least once since it started (reachable soon after).
    bool onionPublished() const { return m_published; }

    // POST body to http://<onion>.onion<path> through Tor. done gets the HTTP status, or -1 when it
    // couldn't be delivered (Tor not ready, bad address, no answer within 30 s). Always called once,
    // never from inside post().
    void post(const QString &onion, const QString &path, const QByteArray &body, std::function<void(int status)> done);

    // Bodies of POST /api/huginn/v1/message that came in through the onion service. The return
    // value is the HTTP status sent back (200 taken, anything else refused). Without a handler: 503.
    void onMessage(std::function<int(const QByteArray &body)> handler) { m_handler = std::move(handler); }

    // 56 characters of base32 (a-z, 2-7); a trailing ".onion" is allowed.
    static bool validOnion(const QString &onion);

    // --tor-selftest: start Tor, reach 100 %, make an onion, post to it through Tor, expect 200.
    static bool selfTest(int seconds = 120);

Q_SIGNALS:
    void progressChanged();
    void readyChanged();
    void stateChanged();
    void onionChanged();

private:
    struct Command {
        QByteArray line;
        std::function<void(bool ok, const QList<QByteArray> &lines)> done;
    };
    struct Conn {
        QByteArray head;
        bool headDone = false;
        QByteArray body;
        qint64 left = 0;
        bool replied = false;
    };

    QString dataDir() const;
    QString keyName() const { return QStringLiteral("tor-onion.key"); }
    bool writeTorrc(const QString &path) const;
    void launch();
    void pollPortFile();
    void authenticate();
    void command(const QByteArray &line, std::function<void(bool ok, const QList<QByteArray> &lines)> done = {});
    void onControlData();
    void onControlLine(const QByteArray &line);
    void onEvent(const QList<QByteArray> &lines);
    void readBootstrap(const QByteArray &text);
    void afterAuth();
    void loadKey();
    void reloadKey();
    void addOnion();
    void querySocks();
    void onProcessGone();
    void resetControl();
    void setState(const QString &state);
    void setProgress(int p);
    void updateReady();

    void onConnection();
    void onHttpData(QTcpSocket *s);
    void reply(QTcpSocket *s, int code, const QByteArray &extra = QByteArray());

    static QString quoted(const QString &path);

    QPointer<Vault> m_vault;
    QString m_dataDir;
    QProcess *m_proc = nullptr;
    QTcpSocket *m_control = nullptr;
    QByteArray m_controlBuf;
    QQueue<Command> m_commands;
    QList<QByteArray> m_reply;
    QList<QByteArray> m_event;
    bool m_inData = false;
    bool m_dataIsEvent = false;
    bool m_authed = false;
    QTimer m_portPoll;
    QTimer m_bootPoll;
    int m_portTries = 0;
    int m_controlPort = 0;
    int m_socksPort = 0;
    int m_progress = 0;
    bool m_ready = false;
    QString m_state = QStringLiteral("off");
    bool m_wanted = false;     // start() was called and stop() wasn't
    int m_restarts = 0;
    QStringList m_log;         // the last lines tor wrote, for the self-test
    QByteArray m_logBuf;

    // Onion service
    QByteArray m_key;          // "ED25519-V3:<base64>", or empty
    QString m_onion;
    bool m_keyLoaded = false;  // tried the Vault (or there is none)
    bool m_onionAdded = false; // ADD_ONION sent to this tor process
    bool m_published = false;

    QTcpServer m_server;
    QHash<QTcpSocket *, Conn> m_conns;
    std::function<int(const QByteArray &)> m_handler;
    QNetworkAccessManager m_net;
};
