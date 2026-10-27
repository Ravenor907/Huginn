#pragma once

#include <QJsonObject>
#include <QObject>
#include <QSet>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <functional>

class CallSession;
class CallSignal;
class QSoundEffect;
class RelayCredentials;
class Vault;
struct TurnLogin;

// Calls for QML (registered as the "Calls" singleton): one 1:1 call at a time, the ringtone and the
// desktop notification for an incoming call, and the call history (kept encrypted in the Vault as
// calls.json, like everything else in Chat). No IP address ever reaches anything shown here.
class CallManager : public QObject
{
    Q_OBJECT
    // "idle", "calling", "ringing" (they're being rung), "incoming", "connecting", "active",
    // "reconnecting", "ended" (shown for a moment, then idle again).
    Q_PROPERTY(QString state READ state NOTIFY changed)
    Q_PROPERTY(bool inCall READ inCall NOTIFY changed)
    Q_PROPERTY(QString peerKey READ peerKey NOTIFY changed)
    Q_PROPERTY(QString peerName READ peerName NOTIFY changed)
    Q_PROPERTY(QString statusText READ statusText NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(bool muted READ muted NOTIFY changed)
    Q_PROPERTY(bool peerMuted READ peerMuted NOTIFY changed)
    Q_PROPERTY(bool peerSpeaking READ peerSpeaking NOTIFY changed)
    Q_PROPERTY(bool relayLive READ relayLive NOTIFY changed)
    Q_PROPERTY(bool wifiLive READ wifiLive NOTIFY changed)
    Q_PROPERTY(bool bleLive READ bleLive NOTIFY changed)
    Q_PROPERTY(QString outputName READ outputName NOTIFY changed)
    Q_PROPERTY(QVariantList history READ history NOTIFY historyChanged)
    Q_PROPERTY(int missedCount READ missedCount NOTIFY historyChanged)
    Q_PROPERTY(bool available READ available CONSTANT)

public:
    CallManager(CallSignal *signal, Vault *vault, QObject *parent = nullptr);
    ~CallManager() override;

    QString state() const;
    bool inCall() const;
    QString peerKey() const;
    QString peerName() const;
    QString statusText() const;
    int elapsed() const;
    bool muted() const;
    bool peerMuted() const;
    bool peerSpeaking() const;
    bool relayLive() const;
    bool wifiLive() const;
    bool bleLive() const;
    QString outputName() const;
    QVariantList history() const { return m_history; }
    int missedCount() const;
    bool available() const;

    // "" when the call is starting, otherwise what to tell the person.
    Q_INVOKABLE QString call(const QString &key);
    Q_INVOKABLE bool canCall(const QString &key) const;
    // A conversation with key is open: fetch the relay login now (cached for two hours), so a call
    // doesn't wait for the relay Worker. At most one try a minute.
    Q_INVOKABLE void prepare(const QString &key);
    Q_INVOKABLE void answer();
    Q_INVOKABLE void decline();
    Q_INVOKABLE void hangup();
    Q_INVOKABLE void toggleMute();
    Q_INVOKABLE void nextOutput();
    Q_INVOKABLE void markSeen();                 // Calls page opened: missed calls are seen
    Q_INVOKABLE void deleteEntry(const QString &id);
    Q_INVOKABLE void clearHistory();
    Q_INVOKABLE QString duration(int seconds) const;

    // Set by main.cpp: true while this device is in a voice room (1:1 offers then get busy, 6.6).
    std::function<bool()> busyElsewhere;
    // A TURN login from the relay Worker (also used by voice rooms, so one cache serves both).
    void getLogin(std::function<void(const TurnLogin &, const QString &)> done);

Q_SIGNALS:
    void changed();
    void tick();
    void historyChanged();
    void incoming(const QString &name);
    void finished(const QString &text);   // for a toast

private:
    void onSignal(const QString &from, const QJsonObject &msg);
    void adopt(CallSession *s);
    void sessionEnded();
    void addHistory(const QString &key, bool out, const QString &result, qint64 started, int seconds);
    void load();
    void save();
    void ring(bool on);
    void notifyIncoming(bool on);
    Q_SLOT void onNotificationAction(uint id, const QString &action);

    CallSignal *m_signal;
    QPointer<Vault> m_vault;
    RelayCredentials *m_relay;
    QPointer<CallSession> m_session;
    QString m_lastPeer, m_endText;
    QTimer m_ticker, m_clear, m_ringer;
    QSoundEffect *m_ringtone = nullptr;
    QVariantList m_history;      // newest first: {id, key, name, out, result, started, seconds, missed}
    qint64 m_seen = 0;           // missed calls after this (ms) count in the badge
    qint64 m_started = 0;
    qint64 m_preparedAt = 0;
    uint m_notification = 0;
    QSet<QString> m_handled;     // call ids already answered with busy or logged as missed
    bool m_loaded = false;
};
