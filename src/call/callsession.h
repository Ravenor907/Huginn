#pragma once

#include "callcrypto.h"
#include "stun.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <functional>

class CallMedia;
class CallSignal;
class QUdpSocket;
class TurnClient;
struct TurnLogin;

// One 1:1 call, from the first offer to the last hangup (docs/protocol-0.6.md 5.2, with the owner's
// change: no STUN and no direct internet path). The offer, answer and cands carry only:
//   cands: [{"t":"relay","a":"<TURN relayed IP>","p":<port>}]   when a relay allocation is up
//          [{"t":"lan","p":<UDP port>}]                          when Chat reaches them on Wi-Fi
//   lp:    <the same UDP port>                                    likewise (Muninn's first form)
// Both apps send the lan port both ways and accept either. "lan" has no address: each side already
// knows the other's Wi-Fi address from Chat, so no IP of the person is ever put in a message. Timings are the spec's: offer every 3 s for up to 45 s,
// answer every 2 s until the first media (15 s at most), hangup, decline and busy 3 times over 2 s.
class CallSession : public QObject
{
    Q_OBJECT
public:
    enum State { Calling, Ringing, Incoming, Connecting, Active, Reconnecting, Ended };
    Q_ENUM(State)

    // getLogin fetches a TURN login (nullptr: no relay, as in the self-test's loopback).
    using LoginFetcher = std::function<void(std::function<void(const TurnLogin &, const QString &)>)>;

    CallSession(CallSignal *signal, const QString &peerKey, LoginFetcher getLogin, QObject *parent = nullptr);
    ~CallSession() override;

    // Outgoing.
    void start();
    // Incoming: the offer (already checked by takeOffer).
    static QString checkOffer(const QJsonObject &offer, const QString &fromKey, qint64 nowMs);
    // The candidates of an offer, answer or cands message: the first "relay" with a literal IP, and
    // the Wi-Fi port from "lp" or else the first "lan" candidate. ok is false with more than 8.
    struct Cands {
        Stun::Address relay;
        quint16 lanPort = 0;
        bool ok = true;
    };
    static Cands parseCands(const QJsonObject &msg);
    void takeOffer(const QJsonObject &offer);
    void answer();
    void decline();
    void hangup(const QString &why = QStringLiteral("hangup"));
    void setMuted(bool on);
    // Every room "call" message for this call id from the peer.
    void handle(const QJsonObject &msg);

    State state() const { return m_state; }
    QString callId() const { return m_callId; }
    QString peerKey() const { return m_peer; }
    bool outgoing() const { return m_offerer; }
    bool muted() const { return m_muted; }
    bool peerMuted() const { return m_peerMuted; }
    bool wasAnswered() const { return m_answered; }
    qint64 activeMs() const { return m_activeClock.isValid() ? m_activeClock.elapsed() : 0; }
    // What to tell the person when it ended: "Call ended", "No answer", "Declined", "Busy",
    // "Can't connect", "Call dropped", "Their app can't take this call".
    QString endText() const { return m_endText; }
    CallMedia *media() const { return m_media; }
    bool relayUp() const;
    bool viaTls() const;
    // When we are the offerer: the offer's sealed body is resent as built (same JSON every time).
    QJsonObject offerMessage() const { return m_offer; }

    bool useDevices = true;   // false: no microphone or speaker (self-test, voice rooms)
    QJsonObject offerExtra;   // added to the offer: a voice room's "g" and "ch" (section 6)
    std::function<QString(const QString &)> lanHostOverride;   // self-test: where the peer's "Wi-Fi" is

Q_SIGNALS:
    void stateChanged();
    void pathsChanged();
    void mutedChanged();
    void speakingChanged();
    void ended();

private:
    void setState(State s);
    void send(const QJsonObject &o);
    void sendThrice(const QJsonObject &o);
    QJsonArray cands() const;
    void addCands(QJsonObject &o) const;   // "cands" and "lp"
    void applyCands(const QJsonObject &msg);
    void startRelay();
    void relayReady();
    void sendOffer();
    void sendAnswer();
    bool startMedia();
    void finish(const QString &text);
    QString lanHost() const;
    QJsonObject signedPart(const QString &t);

    CallSignal *m_signal;
    QString m_peer, m_callId;
    LoginFetcher m_getLogin;
    bool m_offerer = false;
    State m_state = Calling;
    CallCrypto::Ephemeral m_eph;
    QByteArray m_theirEph;        // DER
    QJsonObject m_offer, m_answer;
    QPointer<TurnClient> m_turn;
    bool m_relayDone = false;     // allocated or given up
    QUdpSocket *m_lan = nullptr;  // until the media takes it
    CallMedia *m_media = nullptr;
    Stun::Address m_peerRelayed;
    quint16 m_peerLanPort = 0;
    QTimer m_resend, m_give, m_noPath, m_watch;
    qint64 m_lastCands = 0;
    int m_answerSends = 0;
    bool m_muted = false, m_peerMuted = false, m_answered = false, m_offerSent = false, m_answerSent = false;
    QElapsedTimer m_activeClock, m_downSince;
    QString m_endText;
};
