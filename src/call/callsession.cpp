#include "callsession.h"

#include "callmedia.h"
#include "callsignal.h"
#include "turnclient.h"

#include <QDateTime>
#include <QUdpSocket>

namespace
{
const char kCodec[] = "opus16";
qint64 wallMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

CallSession::CallSession(CallSignal *signal, const QString &peerKey, LoginFetcher getLogin, QObject *parent)
    : QObject(parent), m_signal(signal), m_peer(peerKey), m_getLogin(std::move(getLogin))
{
    m_give.setSingleShot(true);
    m_noPath.setSingleShot(true);
    connect(&m_noPath, &QTimer::timeout, this, [this] {
        if (m_media && m_media->anyLive())
            return;
        hangup(QStringLiteral("nopath"));
    });
    // Paths while the call runs: "Reconnecting" when every one is dead, "Call dropped" after 20 s.
    m_watch.setInterval(500);
    connect(&m_watch, &QTimer::timeout, this, [this] {
        if (!m_media)
            return;
        if (m_media->anyLive()) {
            m_downSince.invalidate();
            if (m_state == Connecting || m_state == Reconnecting) {
                if (!m_activeClock.isValid())
                    m_activeClock.start();
                setState(Active);
            }
            return;
        }
        if (m_state == Active) {
            if (!m_downSince.isValid())
                m_downSince.start();
            else if (m_downSince.elapsed() > 1500)
                setState(Reconnecting);
        } else if (m_state == Reconnecting && m_downSince.isValid() && m_downSince.elapsed() > 20000) {
            hangup(QStringLiteral("dropped"));
        }
    });
}

CallSession::~CallSession()
{
    if (m_media)
        m_media->stop();
    if (m_turn)
        m_turn->stop();
}

bool CallSession::relayUp() const { return m_turn && m_turn->ready(); }
bool CallSession::viaTls() const { return m_turn && m_turn->overTls(); }

void CallSession::setState(State s)
{
    if (s == m_state)
        return;
    m_state = s;
    Q_EMIT stateChanged();
}

QString CallSession::lanHost() const
{
    if (lanHostOverride)
        return lanHostOverride(m_peer);
    return m_signal ? m_signal->lanHost(m_peer) : QString();
}

void CallSession::send(const QJsonObject &o)
{
    if (m_signal)
        m_signal->send(m_peer, o);
}

void CallSession::sendThrice(const QJsonObject &o)
{
    // 3 times over 2 s, then never again. Bound to the signal, so it goes on after this call is gone.
    send(o);
    QPointer<CallSignal> sig = m_signal;
    const QString peer = m_peer;
    for (int ms : {1000, 2000})
        QTimer::singleShot(ms, m_signal, [sig, peer, o] {
            if (sig)
                sig->send(peer, o);
        });
}

QJsonArray CallSession::cands() const
{
    QJsonArray out;
    if (m_turn && m_turn->ready()) {
        const Stun::Address r = m_turn->relayed();
        out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("relay")}, {QStringLiteral("a"), CallNet::toHost(r).toString()},
                               {QStringLiteral("p"), int(r.port)}});
    }
    const quint16 lan = m_lan ? m_lan->localPort() : 0;
    if (lan && !lanHost().isEmpty())
        out.append(QJsonObject{{QStringLiteral("t"), QStringLiteral("lan")}, {QStringLiteral("p"), int(lan)}});
    return out;
}

void CallSession::addCands(QJsonObject &o) const
{
    const QJsonArray c = cands();
    o.insert(QStringLiteral("cands"), c);
    o.remove(QStringLiteral("lp"));
    for (const QJsonValue &v : c)
        if (str(v.toObject(), "t") == QLatin1String("lan"))
            o.insert(QStringLiteral("lp"), v.toObject().value(QStringLiteral("p")).toInt());
}

CallSession::Cands CallSession::parseCands(const QJsonObject &msg)
{
    Cands out;
    const QJsonArray c = msg.value(QStringLiteral("cands")).toArray();
    if (c.size() > 8) {
        out.ok = false;
        return out;
    }
    auto portOf = [](const QJsonValue &v) {
        const double d = v.toDouble(-1);
        return d >= 1 && d <= 65535 && d == double(int(d)) ? quint16(d) : quint16(0);
    };
    for (const QJsonValue &v : c) {
        const QJsonObject o = v.toObject();
        const QString t = str(o, "t");
        const quint16 port = portOf(o.value(QStringLiteral("p")));
        if (!port)
            continue;
        if (t == QLatin1String("relay") && !out.relay.valid()) {
            // A relay address is always a literal IP (the TURN server hands it out).
            QHostAddress a;
            if (a.setAddress(str(o, "a")))
                out.relay = CallNet::fromHost(a, port);
        } else if (t == QLatin1String("lan") && !out.lanPort) {
            out.lanPort = port;
        }
        // "host" and "srflx" (direct internet candidates) are never used.
    }
    if (const quint16 lp = portOf(msg.value(QStringLiteral("lp"))))
        out.lanPort = lp;
    return out;
}

void CallSession::applyCands(const QJsonObject &msg)
{
    const Cands c = parseCands(msg);
    if (!c.ok)
        return;
    if (c.relay.valid())
        m_peerRelayed = c.relay;
    if (c.lanPort)
        m_peerLanPort = c.lanPort;
    if (m_media) {
        if (m_peerRelayed.valid() && m_turn)
            m_media->setRelay(m_turn, m_peerRelayed);
        const QString host = lanHost();
        if (!host.isEmpty())
            m_media->setLanPeer(QHostAddress(host), m_peerLanPort);
    }
}

QJsonObject CallSession::signedPart(const QString &t)
{
    const QString eph = QString::fromLatin1(m_eph.publicDer.toBase64());
    const QByteArray sig = m_signal ? m_signal->sign(CallCrypto::signedBytes(t, m_callId, eph)) : QByteArray();
    return QJsonObject{{QStringLiteral("t"), t}, {QStringLiteral("call"), m_callId}, {QStringLiteral("eph"), eph},
                       {QStringLiteral("sig"), QString::fromLatin1(sig.toBase64())}};
}

// ------------------------------------------------------------------ relay

void CallSession::startRelay()
{
    if (!m_getLogin) {
        m_relayDone = true;
        relayReady();
        return;
    }
    QPointer<CallSession> self(this);
    m_getLogin([self](const TurnLogin &login, const QString &) {
        if (!self || self->m_state == Ended)
            return;
        if (!login.valid()) {
            self->m_relayDone = true;
            self->relayReady();
            return;
        }
        self->m_turn = new TurnClient(login, self);
        connect(self->m_turn, &TurnClient::allocated, self, [self] {
            self->m_relayDone = true;
            if (self->m_media && self->m_peerRelayed.valid())
                self->m_media->setRelay(self->m_turn, self->m_peerRelayed);
            self->relayReady();
            Q_EMIT self->pathsChanged();
        });
        connect(self->m_turn, &TurnClient::failed, self, [self](const QString &) {
            self->m_relayDone = true;
            self->relayReady();
            Q_EMIT self->pathsChanged();
        });
        connect(self->m_turn, &TurnClient::data, self, [self](const Stun::Address &from, const QByteArray &bytes) {
            if (self->m_media && from == self->m_peerRelayed)
                self->m_media->receive(bytes, CallMedia::Relay);
        });
        self->m_turn->start();
    });
}

void CallSession::relayReady()
{
    // The offer waits for the relay (at most 4 s); anything later goes out as "cands".
    if (m_offerer && !m_offerSent && m_state == Calling) {
        sendOffer();
        return;
    }
    if ((m_offerSent || m_answerSent) && m_state != Ended && wallMs() - m_lastCands >= 2000) {
        m_lastCands = wallMs();
        QJsonObject o{{QStringLiteral("t"), QStringLiteral("cands")}, {QStringLiteral("call"), m_callId}};
        addCands(o);
        send(o);
    }
}

// ------------------------------------------------------------------ outgoing

void CallSession::start()
{
    m_offerer = true;
    m_callId = CallCrypto::newCallId();
    m_eph = CallCrypto::newEphemeral();
    m_lan = CallMedia::bindLan(this);
    setState(Calling);
    startRelay();
    m_give.disconnect(this);
    connect(&m_give, &QTimer::timeout, this, [this] {
        if (!m_offerSent)
            sendOffer();
    });
    m_give.start(4000);
}

void CallSession::sendOffer()
{
    m_offerSent = true;
    m_give.stop();
    QJsonObject o = signedPart(QStringLiteral("offer"));
    o.insert(QStringLiteral("ts"), wallMs());   // an integer on the wire, like Muninn's
    addCands(o);
    o.insert(QStringLiteral("ble"), m_signal && m_signal->bleReady(m_peer));
    o.insert(QStringLiteral("codec"), QString::fromLatin1(kCodec));
    m_offer = o;
    send(o);
    // Every 3 s until they ring, answer, decline or are busy; no answer after 45 s.
    m_resend.disconnect(this);
    connect(&m_resend, &QTimer::timeout, this, [this] {
        if (m_state == Calling || m_state == Ringing)
            send(m_offer);
    });
    m_resend.start(3000);
    QTimer::singleShot(45000, this, [this] {
        if (m_state == Calling || m_state == Ringing)
            hangup(QStringLiteral("noanswer"));
    });
}

// ------------------------------------------------------------------ incoming

QString CallSession::checkOffer(const QJsonObject &o, const QString &fromKey, qint64 now)
{
    const QString callId = str(o, "call"), eph = str(o, "eph");
    if (CallCrypto::callIdBytes(callId).isEmpty() || eph.isEmpty())
        return QStringLiteral("malformed");
    const QByteArray sig = QByteArray::fromBase64(str(o, "sig").toLatin1());
    if (!CallCrypto::verifyDer(QByteArray::fromBase64(fromKey.toLatin1()), CallCrypto::signedBytes(QStringLiteral("offer"), callId, eph), sig))
        return QStringLiteral("signature");
    const qint64 ts = qint64(o.value(QStringLiteral("ts")).toDouble());
    if (ts < now - 60000 || ts > now + 120000)
        return QStringLiteral("stale");   // sat on a relay: a missed call, not a ringing one
    if (!parseCands(o).ok)
        return QStringLiteral("malformed");   // more than 8 candidates
    if (str(o, "codec") != QLatin1String(kCodec))
        return QStringLiteral("codec");
    return QString();
}

void CallSession::takeOffer(const QJsonObject &offer)
{
    m_offerer = false;
    m_offer = offer;
    m_callId = str(offer, "call");
    m_theirEph = QByteArray::fromBase64(str(offer, "eph").toLatin1());
    applyCands(offer);
    m_lan = CallMedia::bindLan(this);
    setState(Incoming);
    send(QJsonObject{{QStringLiteral("t"), QStringLiteral("ring")}, {QStringLiteral("call"), m_callId}});
    // Get the relay ready while it rings, so answering is quick.
    startRelay();
    QTimer::singleShot(45000, this, [this] {
        if (m_state == Incoming)
            finish(QStringLiteral("Missed call"));
    });
}

void CallSession::answer()
{
    if (m_state != Incoming)
        return;
    m_answered = true;
    m_eph = CallCrypto::newEphemeral();
    if (!startMedia()) {
        hangup(QStringLiteral("error"));
        return;
    }
    sendAnswer();
    setState(Connecting);
    m_noPath.start(15000);
    m_watch.start();
}

void CallSession::sendAnswer()
{
    m_answerSent = true;
    QJsonObject o = signedPart(QStringLiteral("answer"));
    addCands(o);
    o.insert(QStringLiteral("ble"), m_signal && m_signal->bleReady(m_peer));
    m_answer = o;
    send(o);
    // Every 2 s until the first media packet, for 15 s at most.
    m_answerSends = 1;
    m_resend.disconnect(this);
    connect(&m_resend, &QTimer::timeout, this, [this] {
        if (m_state == Ended || (m_media && m_media->gotMedia()) || ++m_answerSends > 8) {
            m_resend.stop();
            return;
        }
        addCands(m_answer);   // the relay may be up by now
        send(m_answer);
    });
    m_resend.start(2000);
}

void CallSession::decline()
{
    if (m_state != Incoming)
        return;
    sendThrice(QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), m_callId}});
    finish(QStringLiteral("Declined"));
}

// ------------------------------------------------------------------ both

bool CallSession::startMedia()
{
    const QString offerEph = m_offerer ? QString::fromLatin1(m_eph.publicDer.toBase64()) : str(m_offer, "eph");
    const QByteArray offerDer = QByteArray::fromBase64(offerEph.toLatin1());
    const QByteArray answerDer = m_offerer ? m_theirEph : m_eph.publicDer;
    const QByteArray id = CallCrypto::callIdBytes(m_callId);
    QByteArray ikm = CallCrypto::ecdh(m_eph.privateDer, m_theirEph);
    QByteArray root = CallCrypto::root(ikm, id, offerDer, answerDer);
    CallCrypto::wipe(ikm);
    if (root.isEmpty())
        return false;
    m_media = new CallMedia(root, CallCrypto::tag(id), m_offerer, this);
    CallCrypto::wipe(root);   // the media's keyring has its own copy
    m_media->adoptLan(m_lan);
    m_lan = nullptr;
    // The ephemeral private key is never kept past this point.
    CallCrypto::wipe(m_eph.privateDer);
    const QString host = lanHost();
    if (!host.isEmpty())
        m_media->setLanPeer(QHostAddress(host), m_peerLanPort);
    if (m_turn && m_peerRelayed.valid())
        m_media->setRelay(m_turn, m_peerRelayed);
    if (m_signal && m_signal->bleReady(m_peer)) {
        QPointer<CallSignal> sig = m_signal;
        const QString peer = m_peer;
        m_media->setBle([sig, peer](const QByteArray &pkt) { return sig && sig->sendMedia(peer, pkt); });
    }
    connect(m_media, &CallMedia::pathsChanged, this, &CallSession::pathsChanged);
    connect(m_media, &CallMedia::speakingChanged, this, &CallSession::speakingChanged);
    connect(m_media, &CallMedia::firstMedia, this, [this] { m_watch.start(); });
    m_media->setMuted(m_muted);
    m_media->start(useDevices);
    return true;
}

void CallSession::handle(const QJsonObject &msg)
{
    const QString t = str(msg, "t");
    if (m_state == Ended)
        return;
    if (t == QLatin1String("offer")) {
        // A resent offer: ring again, or the same answer again.
        if (!m_offerer) {
            if (m_state == Incoming)
                send(QJsonObject{{QStringLiteral("t"), QStringLiteral("ring")}, {QStringLiteral("call"), m_callId}});
            else if (!m_answer.isEmpty())
                send(m_answer);
        }
        return;
    }
    if (t == QLatin1String("ring")) {
        if (m_offerer && m_state == Calling)
            setState(Ringing);
        return;
    }
    if (t == QLatin1String("answer")) {
        if (!m_offerer || (m_state != Calling && m_state != Ringing))
            return;
        const QString eph = str(msg, "eph");
        const QByteArray sig = QByteArray::fromBase64(str(msg, "sig").toLatin1());
        if (!CallCrypto::verifyDer(QByteArray::fromBase64(m_peer.toLatin1()), CallCrypto::signedBytes(QStringLiteral("answer"), m_callId, eph), sig))
            return;   // refused: not signed by the person we called
        if (!parseCands(msg).ok)
            return;   // malformed: more than 8 candidates
        m_resend.stop();
        m_answered = true;
        m_theirEph = QByteArray::fromBase64(eph.toLatin1());
        applyCands(msg);
        if (!startMedia()) {
            hangup(QStringLiteral("error"));
            return;
        }
        setState(Connecting);
        m_noPath.start(15000);
        m_watch.start();
        return;
    }
    if (t == QLatin1String("cands")) {
        applyCands(msg);
        return;
    }
    if (t == QLatin1String("mute")) {
        m_peerMuted = msg.value(QStringLiteral("on")).toBool();
        Q_EMIT mutedChanged();
        return;
    }
    if (t == QLatin1String("decline")) {
        finish(QStringLiteral("Declined"));
        return;
    }
    if (t == QLatin1String("busy")) {
        finish(QStringLiteral("Busy"));
        return;
    }
    if (t == QLatin1String("hangup")) {
        const QString why = str(msg, "why");
        finish(why == QLatin1String("nopath") ? QStringLiteral("Can't connect")
               : why == QLatin1String("dropped") ? QStringLiteral("Call dropped")
               : why == QLatin1String("noanswer") && m_state == Incoming ? QStringLiteral("Missed call")
               : QStringLiteral("Call ended"));
    }
}

void CallSession::setMuted(bool on)
{
    if (on == m_muted)
        return;
    m_muted = on;
    if (m_media)
        m_media->setMuted(on);
    if (m_state != Ended && (m_answered || m_offerSent))
        send(QJsonObject{{QStringLiteral("t"), QStringLiteral("mute")}, {QStringLiteral("call"), m_callId}, {QStringLiteral("on"), on}});
    Q_EMIT mutedChanged();
}

void CallSession::hangup(const QString &why)
{
    if (m_state == Ended)
        return;
    if (m_offerSent || m_answerSent || m_state == Incoming)
        sendThrice(QJsonObject{{QStringLiteral("t"), QStringLiteral("hangup")}, {QStringLiteral("call"), m_callId}, {QStringLiteral("why"), why}});
    finish(why == QLatin1String("nopath") ? QStringLiteral("Can't connect")
           : why == QLatin1String("dropped") ? QStringLiteral("Call dropped")
           : why == QLatin1String("noanswer") ? QStringLiteral("No answer")
           : why == QLatin1String("error") ? QStringLiteral("The call couldn't start")
           : QStringLiteral("Call ended"));
}

void CallSession::finish(const QString &text)
{
    if (m_state == Ended)
        return;
    m_endText = text;
    m_resend.stop();
    m_give.stop();
    m_noPath.stop();
    m_watch.stop();
    if (m_media)
        m_media->stop();
    if (m_turn)
        m_turn->stop();
    CallCrypto::wipe(m_eph.privateDer);
    setState(Ended);
    Q_EMIT ended();
}
