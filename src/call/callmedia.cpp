#include "callmedia.h"

#include "audioio.h"
#include "turnclient.h"

#include <QRandomGenerator>
#include <algorithm>
#include <QUdpSocket>

namespace
{
const qint64 kLiveMs = 4000;          // a path with nothing authentic for this long is dead
const qint64 kSilenceEveryMs = 400;   // DTX: one silent packet this often
const qint64 kEagerMs = 15000;        // pings to paths not live yet: fast at first
}

CallMedia::CallMedia(const QByteArray &root, const QByteArray &tag, bool offerer, QObject *parent)
    : QObject(parent), m_keys(root), m_tx(&m_keys, offerer ? CallCrypto::O2A : CallCrypto::A2O, tag),
      m_rx(&m_keys, offerer ? CallCrypto::A2O : CallCrypto::O2A, tag)
{
    m_clock.start();
    m_tick.setInterval(50);
    connect(&m_tick, &QTimer::timeout, this, &CallMedia::tick);
}

CallMedia::~CallMedia()
{
    stop();
}

QUdpSocket *CallMedia::bindLan(QObject *parent)
{
    auto *s = new QUdpSocket(parent);
    if (!s->bind(QHostAddress::Any, 0)) {
        delete s;
        return nullptr;
    }
    return s;
}

void CallMedia::adoptLan(QUdpSocket *socket)
{
    if (!socket || m_lan)
        return;
    m_lan = socket;
    m_lan->setParent(this);
    connect(m_lan, &QUdpSocket::readyRead, this, &CallMedia::onLan);
    QTimer::singleShot(0, this, &CallMedia::onLan);
}

void CallMedia::setLanPeer(const QHostAddress &host, quint16 port)
{
    m_lanHost = host;
    if (port)
        m_lanPeerPort = port;
}

void CallMedia::setRelay(TurnClient *turn, const Stun::Address &peerRelayed)
{
    m_turn = turn;
    if (peerRelayed == m_seenRelayed)
        m_seenRelayed = Stun::Address();
    m_peerRelayed = peerRelayed;
    if (turn && peerRelayed.valid())
        turn->addPeer(peerRelayed);
}

void CallMedia::start(bool devices)
{
    if (m_running)
        return;
    m_running = true;
    m_started = now();
    if (devices) {
        m_audio = new AudioIO(this);
        m_audio->pull = [this](qint16 *pcm) { onPull(pcm); };
        connect(m_audio, &AudioIO::captured, this, [this](const QByteArray &pcm) {
            if (pcm.size() == AudioDsp::kFrame * 2)
                onFrame(reinterpret_cast<const qint16 *>(pcm.constData()));
        });
        m_audio->start();
    }
    m_tick.start();
    tick();
}

void CallMedia::stop()
{
    m_running = false;
    m_tick.stop();
    if (m_audio) {
        m_audio->stop();
        m_audio->deleteLater();
        m_audio = nullptr;
    }
    if (m_lan) {
        m_lan->close();
        m_lan->deleteLater();
        m_lan = nullptr;
    }
}

bool CallMedia::live(Path p) const { return now() - m_paths[p].lastRx < kLiveMs; }

bool CallMedia::candidate(Path p) const
{
    switch (p) {
    case Relay: return m_turn && m_turn->ready() && m_peerRelayed.valid();
    case Wifi: return m_lan && !m_lanHost.isNull() && m_lanPeerPort != 0;
    case Ble: return bool(m_bleSend);
    }
    return false;
}

void CallMedia::sendOn(Path p, const QByteArray &pkt)
{
    if (pkt.isEmpty())
        return;
    switch (p) {
    case Relay:
        if (m_turn)
            for (const Stun::Address &a : relayTargets())
                m_turn->send(a, pkt);
        break;
    case Wifi:
        if (m_lan && m_lanPeerPort)
            m_lan->writeDatagram(pkt, m_lanHost, m_lanPeerPort);
        break;
    case Ble:
        if (m_bleSend)
            m_bleSend(pkt);
        break;
    }
}

void CallMedia::sendPing(Path p)
{
    // 8 random bytes; the pong carries them back. The last one sent times the round trip.
    QByteArray payload(8, 0);
    const quint64 r = QRandomGenerator::system()->generate64();
    for (int i = 0; i < 8; ++i)
        payload[i] = char(r >> (8 * i));
    m_pingPayload = payload;
    m_pingSentAt = now();
    sendOn(p, m_tx.seal(CallCrypto::Ping, quint32(now() - m_started), payload));
    m_paths[p].lastPing = now();
    ++m_stats.pings;
    forgetOld();
}

void CallMedia::tick()
{
    const qint64 t = now();
    for (int i = 0; i < 3; ++i) {
        const Path p = Path(i);
        if (!candidate(p))
            continue;
        const qint64 every = live(p) ? 1000 : (t - m_started < kEagerMs ? 250 : 2000);
        if (t - m_paths[i].lastPing >= every)
            sendPing(p);
    }
    bool changed = false;
    for (int i = 0; i < 3; ++i) {
        const bool l = live(Path(i));
        if (l != m_wasLive[i]) {
            m_wasLive[i] = l;
            changed = true;
        }
    }
    if (changed)
        Q_EMIT pathsChanged();
}

void CallMedia::onFrame(const qint16 *pcm)
{
    if (!m_running)
        return;
    const quint32 ts = m_frames++ * quint32(JitterBuffer::kFrameMs);
    if (m_muted || !m_enc.ok())
        return;   // muted: no audio packets at all (pings go on)
    const QByteArray opus = m_enc.encode(pcm);
    if (opus.isEmpty())
        return;
    if (OpusEncoder16::isSilence(opus) && now() - m_lastAudioSent < kSilenceEveryMs)
        return;
    const QByteArray pkt = m_tx.seal(CallCrypto::Audio, ts, opus);
    m_lastAudioSent = now();
    ++m_stats.audioSent;
    if (dropEvery > 0 && m_stats.audioSent % dropEvery == 0)
        return;
    for (int i = 0; i < 3; ++i)
        if (live(Path(i)))
            sendOn(Path(i), pkt);
    forgetOld();
}

void CallMedia::onPull(qint16 *pcm)
{
    const JitterBuffer::Frame f = m_jitter.pop(now());
    bool ok = false;
    switch (f.kind) {
    case JitterBuffer::Packet: ok = m_dec.decode(f.data, pcm); break;
    case JitterBuffer::Fec: ok = m_dec.decodeFec(f.data, pcm); break;
    case JitterBuffer::Conceal: ok = m_dec.conceal(pcm); break;
    case JitterBuffer::Nothing: std::fill(pcm, pcm + AudioDsp::kFrame, qint16(0)); break;
    }
    if (ok)
        ++m_stats.decoded;
    const bool was = m_speaking.on();
    if (m_speaking.frame(AudioDsp::levelDb(pcm, AudioDsp::kFrame)) != was)
        Q_EMIT speakingChanged();
}

void CallMedia::onLan()
{
    while (m_lan && m_lan->hasPendingDatagrams()) {
        QByteArray b(int(qMax<qint64>(0, m_lan->pendingDatagramSize())), 0);
        QHostAddress from;
        quint16 port = 0;
        const qint64 n = m_lan->readDatagram(b.data(), b.size(), &from, &port);
        if (n <= 0)
            continue;
        b.resize(int(n));
        // Only from the address Chat knows them at (5.6: the source has to fit).
        if (m_lanHost.isNull() || !from.isEqual(m_lanHost, QHostAddress::TolerantConversion))
            continue;
        handle(b, Wifi, from, port);
    }
}

void CallMedia::receive(const QByteArray &pkt, Path from, const Stun::Address &relayFrom) { handle(pkt, from, QHostAddress(), 0, relayFrom); }

QList<Stun::Address> CallMedia::relayTargets() const
{
    QList<Stun::Address> out;
    if (m_peerRelayed.valid())
        out << m_peerRelayed;
    if (m_seenRelayed.valid() && m_seenRelayed != m_peerRelayed)
        out << m_seenRelayed;
    return out;
}

void CallMedia::handle(const QByteArray &pkt, Path from, const QHostAddress &, quint16 port, const Stun::Address &relayFrom)
{
    if (pkt.isEmpty() || quint8(pkt[0]) != CallCrypto::kVersion)
        return;
    CallCrypto::Receiver::Packet p;
    const QString why = m_rx.open(pkt, &p);
    if (!why.isEmpty()) {
        if (why == QLatin1String("duplicate"))
            ++m_stats.duplicates;
        else
            ++m_stats.refused;
        // A duplicate is still proof this path works.
        if (why == QLatin1String("duplicate"))
            m_paths[from].lastRx = now();
        return;
    }
    if (from == Wifi && port)
        m_lanPeerPort = port;   // a NAT or a firewall may have changed it: answer where it came from
    if (from == Relay && relayFrom.valid() && relayFrom != m_peerRelayed && relayFrom != m_seenRelayed) {
        // Authentic, but relayed from an address they didn't advertise: send there too.
        m_seenRelayed = relayFrom;
        if (m_turn)
            m_turn->addPeer(relayFrom);
    }
    const bool wasLive = live(from);
    m_paths[from].lastRx = now();
    if (!wasLive)
        tick();
    if (!m_gotMedia) {
        m_gotMedia = true;
        Q_EMIT firstMedia();
    }
    switch (p.type) {
    case CallCrypto::Ping:
        sendOn(from, m_tx.seal(CallCrypto::Pong, quint32(now() - m_started), p.payload));
        break;
    case CallCrypto::Pong:
        ++m_stats.pongs;
        if (p.payload == m_pingPayload)
            m_rtt = int(now() - m_pingSentAt);
        break;
    case CallCrypto::Audio:
        ++m_stats.audioIn;
        m_jitter.push(p.ts, p.payload, now());
        break;
    }
    forgetOld();
}

void CallMedia::forgetOld()
{
    // Epochs both directions are past are wiped (5.3).
    const quint32 oldest = qMin(m_tx.epoch(), m_rx.lowEpoch());
    if (oldest > m_keys.oldest())
        m_keys.forgetBefore(oldest);
}
