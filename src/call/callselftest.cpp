#include "callselftest.h"

#include "audiodsp.h"
#include "callcrypto.h"
#include "callmedia.h"
#include "callsession.h"
#include "callsignal.h"
#include "jitterbuffer.h"
#include "opuscodec.h"
#include "stun.h"
#include "turnclient.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QJsonDocument>
#include <QPointer>
#include <QUdpSocket>
#include <cstdio>
#include <functional>

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}

// Runs the event loop until done() is true or ms pass.
bool waitFor(const std::function<bool()> &done, int ms)
{
    QElapsedTimer t;
    t.start();
    while (!done() && t.elapsed() < ms)
        QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
    return done();
}

void relayChecks()
{
    std::printf("relay\n");
    const QByteArray key = QByteArray::fromBase64("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhfmF/C2RDkoJ4+WmZ5pojpPLBUr321s32bluAKC1O0ZSn3ry5dxLS3aPKhaqHZaVvRfx1hZllLyiXxlMG5XlA==");
    const QJsonObject b = QJsonDocument::fromJson(RelayCredentials::body(key, 1800000000, QByteArray(64, 'a'))).object();
    const QString id = b.value(QStringLiteral("id")).toString();
    const QByteArray want = CallCrypto::hmacSha256(QByteArray(64, 'a'), id.toLatin1() + ".1800000000").toHex();
    check(id.size() == 64 && id == QString::fromLatin1(CallCrypto::sha256(key).toHex()) && b.value(QStringLiteral("t")).toDouble() == 1800000000.0
              && b.value(QStringLiteral("t")).isDouble() && b.value(QStringLiteral("sig")).toString().toLatin1() == want,
          "Worker request: id, t as a number, sig over id.t");
    // The same request Muninn's TurnTest.workerBodyMatchesHuginn builds: id is SHA-256 of the raw SPKI DER.
    check(id == QLatin1String("76805a63a6980c18075424a13f32772d02d7c71b4965754c1ce84f5841220327")
              && want == "a0115b4186fe1780017780b84bf5a20f0501934df65efb994e35c88f745e188c",
          "Worker request id and sig match Muninn's");
    {
        QObject ctx;
        QHostAddress got, lit;
        bool done = false, litDone = false;
        CallNet::resolve(QStringLiteral("localhost"), &ctx, [&](const QHostAddress &a) { got = a; done = true; }, 1);
        CallNet::resolve(QStringLiteral("104.30.1.2"), &ctx, [&](const QHostAddress &a) { lit = a; litDone = true; });
        waitFor([&] { return done && litDone; }, 5000);
        check(done && got.isLoopback(), "the relay's name is looked up (localhost answers)");
        check(litDone && lit == QHostAddress(QStringLiteral("104.30.1.2")), "an IP literal needs no lookup");
    }
    const auto s = TurnClient::servers({QStringLiteral("turn:turn.cloudflare.com:3478?transport=udp"), QStringLiteral("turn:turn.cloudflare.com:3478?transport=tcp"),
                                        QStringLiteral("turns:turn.cloudflare.com:5349?transport=tcp"), QStringLiteral("turns:turn.cloudflare.com:443?transport=tcp"),
                                        QStringLiteral("stun:stun.cloudflare.com:3478")});
    check(s.size() == 3 && !s[0].tls && s[0].port == 3478 && s[1].tls && s[1].port == 443 && s[2].tls && s[2].port == 5349
              && s[0].host == QLatin1String("turn.cloudflare.com"),
          "TURN servers: UDP 3478, then TLS 443, then TLS 5349, never STUN");
}

void mediaLoopback()
{
    std::printf("media loopback (local UDP, no TURN)\n");
    if (!opusAvailable()) {
        check(false, "built with Opus");
        return;
    }
    QByteArray root(32, 0);
    for (int i = 0; i < 32; ++i)
        root[i] = char(i * 7 + 1);
    const QByteArray tag = CallCrypto::tag(QByteArray(16, 'x'));
    CallMedia a(root, tag, true), b(root, tag, false);
    QUdpSocket *sa = CallMedia::bindLan(nullptr), *sb = CallMedia::bindLan(nullptr);
    check(sa && sb, "two UDP sockets");
    if (!sa || !sb)
        return;
    const quint16 pa = sa->localPort(), pb = sb->localPort();
    a.adoptLan(sa);
    b.adoptLan(sb);
    a.setLanPeer(QHostAddress(QHostAddress::LocalHost), pb);
    b.setLanPeer(QHostAddress(QHostAddress::LocalHost), pa);
    // A second path that copies every packet: the receiver must keep only the first.
    QPointer<CallMedia> pA(&a), pB(&b);
    a.setBle([pB](const QByteArray &p) { QTimer::singleShot(3, pB, [pB, p] { if (pB) pB->receive(p, CallMedia::Ble); }); return true; });
    b.setBle([pA](const QByteArray &p) { QTimer::singleShot(3, pA, [pA, p] { if (pA) pA->receive(p, CallMedia::Ble); }); return true; });
    a.dropEvery = 10;   // 10 % of A's audio never leaves (on either path)
    a.start(false);
    b.start(false);
    check(waitFor([&] { return a.live(CallMedia::Wifi) && b.live(CallMedia::Wifi) && a.live(CallMedia::Ble) && b.live(CallMedia::Ble); }, 3000),
          "Wi-Fi and Bluetooth paths live both ways");

    qint64 phaseA = 0, phaseB = 0;
    int frames = 0;
    double hzAtB = 0, hzAtA = 0;
    int loudAtB = 0, loudAtA = 0;
    bool speakingB = false;
    QTimer clock;
    clock.setTimerType(Qt::PreciseTimer);
    clock.setInterval(20);
    QObject::connect(&clock, &QTimer::timeout, [&] {
        qint16 pcm[AudioDsp::kFrame];
        AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phaseA);
        a.feed(pcm);
        AudioDsp::tone(pcm, AudioDsp::kFrame, 660, 0.4, &phaseB);
        b.feed(pcm);
        qint16 out[AudioDsp::kFrame];
        b.playFrame(out);
        if (AudioDsp::levelDb(out, AudioDsp::kFrame) > -30) {
            hzAtB += AudioDsp::zeroCrossingHz(out, AudioDsp::kFrame);
            ++loudAtB;
        }
        speakingB = speakingB || b.speaking();
        a.playFrame(out);
        if (AudioDsp::levelDb(out, AudioDsp::kFrame) > -30) {
            hzAtA += AudioDsp::zeroCrossingHz(out, AudioDsp::kFrame);
            ++loudAtA;
        }
        ++frames;
    });
    clock.start();
    waitFor([&] { return frames >= 150; }, 6000);   // 3 seconds of sound
    clock.stop();
    waitFor([] { return false; }, 200);
    std::printf("       A sent %d, B got %d (+%d copies), B played %d, FEC %d, concealed %d, delay %d ms, rtt %d ms\n", a.stats().audioSent,
                b.stats().audioIn, b.stats().duplicates, b.jitter().stats().played, b.jitter().stats().fec, b.jitter().stats().concealed,
                b.jitter().targetMs(), a.rttMs());
    check(b.stats().audioIn >= a.stats().audioSent * 8 / 10 && b.stats().audioIn <= a.stats().audioSent, "B got A's audio, each packet once");
    check(b.stats().duplicates >= b.stats().audioIn / 2, "copies from the second path dropped as duplicates");
    check(b.stats().refused == 0 && a.stats().refused == 0, "nothing refused");
    check(b.jitter().stats().fec + b.jitter().stats().concealed > 0, "lost packets rebuilt (FEC) or concealed");
    check(loudAtB > 80 && hzAtB / qMax(1, loudAtB) > 380 && hzAtB / qMax(1, loudAtB) < 500, "B hears A's 440 Hz tone");
    check(loudAtA > 80 && hzAtA / qMax(1, loudAtA) > 580 && hzAtA / qMax(1, loudAtA) < 740, "A hears B's 660 Hz tone");
    check(speakingB, "speaking indicator");
    a.setMuted(true);
    const int before = b.stats().audioIn;
    for (int i = 0; i < 25; ++i) {
        qint16 pcm[AudioDsp::kFrame];
        AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phaseA);
        a.feed(pcm);
    }
    waitFor([] { return false; }, 200);
    check(b.stats().audioIn == before && b.live(CallMedia::Wifi), "muted: no audio sent, the path stays live on pings");
    a.stop();
    b.stop();
}

struct Side {
    CallCrypto::Ephemeral id;   // stands in for the chat key
    CallSignalHooks *sig = nullptr;
    QPointer<CallSession> session;
};

// Messages exactly as Muninn's CallLogic builds them (CallLogicTest.crossApp checks the same text),
// and the older forms of both apps: each side reads the other's.
void crossApp()
{
    std::printf("Muninn's candidate formats\n");
    auto parse = [](const char *json) { return CallSession::parseCands(QJsonDocument::fromJson(QByteArray(json)).object()); };
    const Stun::Address relay = CallNet::fromHost(QHostAddress(QStringLiteral("104.30.1.2")), 61000);
    CallSession::Cands c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"relay","a":"104.30.1.2","p":61000},{"t":"lan","p":40000}],"lp":40000})");
    check(c.ok && c.relay == relay && c.lanPort == 40000, "Muninn 0.6: relay, lan candidate and lp");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"relay","a":"104.30.1.2","p":61000}],"lp":40001})");
    check(c.ok && c.relay == relay && c.lanPort == 40001, "lp alone (Muninn before the lan candidate)");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"lan","p":40002}]})");
    check(c.ok && !c.relay.valid() && c.lanPort == 40002, "a lan candidate alone (Huginn before lp)");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"host","a":"192.168.1.20","p":40312},{"t":"srflx","a":"203.0.113.7","p":51820},{"t":"relay","a":"evil.example","p":3478},{"t":"relay","a":"104.30.1.2","p":61000}]})");
    check(c.ok && c.relay == relay && c.lanPort == 0, "host, srflx and a relay by name are ignored");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{},{},{},{},{},{},{},{},{}]})");
    check(!c.ok, "more than 8 candidates is malformed");
}

void signalingLoopback()
{
    std::printf("signaling loopback (two sessions in-process)\n");
    Side a, b;
    a.id = CallCrypto::newEphemeral();
    b.id = CallCrypto::newEphemeral();
    const QString keyA = QString::fromLatin1(a.id.publicDer.toBase64()), keyB = QString::fromLatin1(b.id.publicDer.toBase64());
    a.sig = new CallSignalHooks();
    b.sig = new CallSignalHooks();
    for (Side *s : {&a, &b}) {
        Side *other = s == &a ? &b : &a;
        const QString mine = s == &a ? keyA : keyB;
        s->sig->onMyKey = [mine] { return mine; };
        s->sig->onSign = [s](const QByteArray &bytes) { return CallCrypto::signDer(s->id.privateDer, bytes); };
        s->sig->onNameOf = [](const QString &) { return QStringLiteral("Test"); };
        s->sig->onCanCall = [](const QString &) { return true; };
        s->sig->onLanHost = [](const QString &) { return QStringLiteral("127.0.0.1"); };
        QPointer<CallSignalHooks> to = other->sig;
        s->sig->onSend = [to, mine](const QString &, const QJsonObject &m) {
            QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
        };
    }
    // B: rings on the offer and answers after a moment, like a person would.
    QObject::connect(b.sig, &CallSignal::received, b.sig, [&b](const QString &from, const QJsonObject &m) {
        if (b.session) {
            b.session->handle(m);
            return;
        }
        if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer"))
            return;
        if (!CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
            return;
        b.session = new CallSession(b.sig, from, nullptr, b.sig);
        b.session->useDevices = false;
        b.session->takeOffer(m);
        QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
    });
    QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
        if (a.session)
            a.session->handle(m);
    });
    a.session = new CallSession(a.sig, keyB, nullptr, a.sig);
    a.session->useDevices = false;
    a.session->start();
    check(waitFor([&] { return b.session && b.session->state() == CallSession::Incoming; }, 3000), "B rings");
    check(waitFor([&] { return a.session->state() == CallSession::Ringing || a.session->state() == CallSession::Connecting
                               || a.session->state() == CallSession::Active; }, 3000),
          "A hears it ringing");
    check(waitFor([&] { return a.session->state() == CallSession::Active && b.session->state() == CallSession::Active; }, 6000),
          "both sides connected (answer signed, keys agree, Wi-Fi path live)");
    check(a.session->media() && a.session->media()->live(CallMedia::Wifi) && !a.session->media()->live(CallMedia::Relay), "over Wi-Fi only (no relay in this test)");
    a.session->setMuted(true);
    check(waitFor([&] { return b.session->peerMuted(); }, 2000), "mute reaches the other side");
    a.session->hangup();
    check(waitFor([&] { return b.session->state() == CallSession::Ended; }, 3000) && b.session->endText() == QLatin1String("Call ended"),
          "hangup ends both sides");

    // A forged answer (signed by someone else) is refused.
    Side c;
    c.id = CallCrypto::newEphemeral();
    CallSignalHooks lone;
    QJsonObject lastOffer;
    lone.onSend = [&lastOffer](const QString &, const QJsonObject &m) { if (m.value(QStringLiteral("t")).toString() == QLatin1String("offer")) lastOffer = m; };
    lone.onSign = [&a](const QByteArray &bytes) { return CallCrypto::signDer(a.id.privateDer, bytes); };
    lone.onCanCall = [](const QString &) { return true; };
    CallSession caller(&lone, keyB, nullptr);
    caller.useDevices = false;
    caller.start();
    waitFor([&] { return !lastOffer.isEmpty(); }, 6000);
    const CallCrypto::Ephemeral eph = CallCrypto::newEphemeral();
    const QString e = QString::fromLatin1(eph.publicDer.toBase64());
    const QString callId = caller.callId();
    caller.handle(QJsonObject{{QStringLiteral("t"), QStringLiteral("answer")}, {QStringLiteral("call"), callId}, {QStringLiteral("eph"), e},
                              {QStringLiteral("sig"), QString::fromLatin1(CallCrypto::signDer(c.id.privateDer, CallCrypto::signedBytes(QStringLiteral("answer"), callId, e)).toBase64())}});
    check(caller.state() == CallSession::Calling && !caller.media(), "an answer signed by someone else is refused");
    check(CallSession::checkOffer(lastOffer, keyA, QDateTime::currentMSecsSinceEpoch()).isEmpty(), "our own offer checks out");
    check(CallSession::checkOffer(lastOffer, keyB, QDateTime::currentMSecsSinceEpoch()) == QLatin1String("signature"), "an offer from the wrong key is refused");
    check(CallSession::checkOffer(lastOffer, keyA, QDateTime::currentMSecsSinceEpoch() + 61000) == QLatin1String("stale"), "an offer over 60 s old is a missed call");
    const QJsonArray cands = lastOffer.value(QStringLiteral("cands")).toArray();
    bool noIp = true;
    for (const QJsonValue &v : cands)
        if (v.toObject().value(QStringLiteral("t")).toString() != QLatin1String("relay") && v.toObject().contains(QStringLiteral("a")))
            noIp = false;
    check(noIp, "offers carry no address but the relay's");
    {
        const QJsonObject mine = a.session->offerMessage();
        const CallSession::Cands c = CallSession::parseCands(mine);
        bool lanCand = false;
        for (const QJsonValue &v : mine.value(QStringLiteral("cands")).toArray())
            lanCand = lanCand || v.toObject().value(QStringLiteral("t")).toString() == QLatin1String("lan");
        check(lanCand && mine.value(QStringLiteral("lp")).toInt() == c.lanPort && c.lanPort != 0 && mine.value(QStringLiteral("ts")).isDouble()
                  && QJsonDocument(mine).toJson(QJsonDocument::Compact).contains("\"ts\":" + QByteArray::number(qint64(mine.value(QStringLiteral("ts")).toDouble()))),
              "an offer on Wi-Fi says the port both ways (lan candidate and lp) and ts is a plain integer");
    }
    crossApp();
    caller.hangup();
    delete a.sig;
    delete b.sig;
}
}

bool CallSelfTest::run(const QString &vectorsPath)
{
    g_fail = 0;
    const bool vectors = CallCrypto::selfTest(vectorsPath);
    const bool stun = Stun::selfTest();
    const bool jitter = JitterBuffer::selfTest();
    const bool dsp = AudioDsp::selfTest();
    relayChecks();
    mediaLoopback();
    signalingLoopback();
    const bool ok = vectors && stun && jitter && dsp && g_fail == 0;
    std::printf("call self-test: %s\n", ok ? "all good" : "FAILED");
    std::fflush(stdout);
    return ok;
}
