#pragma once

#include "audiodsp.h"
#include "callcrypto.h"
#include "jitterbuffer.h"
#include "opuscodec.h"
#include "stun.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <functional>
#include <memory>

class AudioIO;
class QUdpSocket;
class TurnClient;

// The sound of one call, on every path at once (docs/protocol-0.6.md 5.4 to 5.6, with the owner's
// change: over the internet only through the TURN relay):
//  - Relay: through our TURN allocation to the peer's relayed address.
//  - Wi-Fi: straight to the peer's own address on the network Chat already reaches them on.
//  - Bluetooth: the MEDIA characteristic, when the BLE link can carry it.
// Every audio packet goes out on every live path with the same seq; the receiver's replay window
// keeps the first copy. A path is live while authentic packets arrive on it (4 s); each live path
// gets a ping every second, the others every 250 ms for the first 15 s, then every 2 s.
// Microphone and speaker come from AudioIO; the self-test feeds and takes PCM instead.
class CallMedia : public QObject
{
    Q_OBJECT
public:
    enum Path { Relay = 0, Wifi = 1, Ble = 2 };

    // root and tag from the call's keys; offerer is true on the side that sent the offer.
    CallMedia(const QByteArray &root, const QByteArray &tag, bool offerer, QObject *parent = nullptr);
    ~CallMedia() override;

    // The UDP socket for the Wi-Fi path (bound by the session before the keys exist, so its port can
    // go in the offer or answer). Takes it over; anything that waited on it is read now.
    void adoptLan(QUdpSocket *socket);
    // A random port on all interfaces, for the Wi-Fi path; nullptr when none can be had.
    static QUdpSocket *bindLan(QObject *parent);
    void setLanPeer(const QHostAddress &host, quint16 port);   // port 0: learned from their first packet
    // peerRelayed: the relayed address the peer advertised. Some relays translate addresses between
    // their sites (Cloudflare anchors each allocation at the nearest data center), so its packets may
    // come from another port or address: the first authentic one from there makes that address a
    // second target (permitted and sent to as well). Media is end-to-end authenticated, so a source
    // can't be faked into this.
    void setRelay(TurnClient *turn, const Stun::Address &peerRelayed);
    void setBle(std::function<bool(const QByteArray &)> send) { m_bleSend = std::move(send); }
    // A media packet that came in over the relay or Bluetooth (Wi-Fi packets arrive by themselves).
    // relayFrom: the peer address the relay says it came from.
    void receive(const QByteArray &pkt, Path from, const Stun::Address &relayFrom = Stun::Address());
    // The relay addresses media goes to: the advertised one, then the one its packets come from.
    QList<Stun::Address> relayTargets() const;

    // devices false: no microphone or speaker (self-test); use feed() and played() instead.
    void start(bool devices = true);
    void stop();
    void setMuted(bool on) { m_muted = on; }
    bool muted() const { return m_muted; }
    AudioIO *audio() const { return m_audio; }

    bool live(Path p) const;
    bool anyLive() const { return live(Relay) || live(Wifi) || live(Ble); }
    bool gotMedia() const { return m_gotMedia; }
    bool speaking() const { return m_speaking.on(); }
    int rttMs() const { return m_rtt; }

    // Self-test: one microphone frame in, and pull one speaker frame out.
    void feed(const qint16 *pcm320) { onFrame(pcm320); }
    void playFrame(qint16 *pcm320) { onPull(pcm320); }
    int dropEvery = 0;           // self-test: leave out every Nth audio packet on purpose
    struct Stats {
        int audioSent = 0, audioIn = 0, duplicates = 0, refused = 0, pings = 0, pongs = 0, decoded = 0;
    };
    const Stats &stats() const { return m_stats; }
    const JitterBuffer &jitter() const { return m_jitter; }

Q_SIGNALS:
    void pathsChanged();
    void firstMedia();          // the first authentic packet from the other side
    void speakingChanged();

private:
    struct PathState {
        qint64 lastRx = -1000000;
        qint64 lastPing = -1000000;
    };
    void onFrame(const qint16 *pcm);
    void onPull(qint16 *pcm);
    void onLan();
    void tick();
    void sendOn(Path p, const QByteArray &pkt);
    bool candidate(Path p) const;
    void handle(const QByteArray &pkt, Path from, const QHostAddress &host, quint16 port, const Stun::Address &relayFrom = Stun::Address());
    void sendPing(Path p);
    void forgetOld();
    qint64 now() const { return m_clock.elapsed(); }

    CallCrypto::Keyring m_keys;
    CallCrypto::Sender m_tx;
    CallCrypto::Receiver m_rx;
    OpusEncoder16 m_enc;
    OpusDecoder16 m_dec;
    JitterBuffer m_jitter;
    AudioDsp::Speaking m_speaking;
    AudioIO *m_audio = nullptr;
    QUdpSocket *m_lan = nullptr;
    QHostAddress m_lanHost;
    quint16 m_lanPeerPort = 0;
    QPointer<TurnClient> m_turn;
    Stun::Address m_peerRelayed, m_seenRelayed;
    std::function<bool(const QByteArray &)> m_bleSend;
    PathState m_paths[3];
    bool m_wasLive[3] = {false, false, false};
    QElapsedTimer m_clock;
    QTimer m_tick;
    qint64 m_started = 0, m_lastAudioSent = -100000;
    quint32 m_frames = 0;
    bool m_muted = false, m_gotMedia = false, m_running = false;
    QByteArray m_pingPayload;
    qint64 m_pingSentAt = 0;
    int m_rtt = 0;
    Stats m_stats;
};
