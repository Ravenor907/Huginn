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
#include <QHash>
#include <QSet>
#include <QUdpSocket>
#include <memory>
#include <vector>
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
        QHostAddress got(QStringLiteral("1.1.1.1")), lit;
        bool done = false, litDone = false;
        // No Tor here: a name must get no answer at all (the system resolver knows "localhost").
        CallNet::resolveViaTor(QStringLiteral("localhost"), &ctx, [&](const QHostAddress &a) { got = a; done = true; }, 1);
        CallNet::resolveViaTor(QStringLiteral("104.30.1.2"), &ctx, [&](const QHostAddress &a) { lit = a; litDone = true; });
        waitFor([&] { return done && litDone; }, 3000);
        check(done && got.isNull(), "the relay's name is looked up through Tor only (no Tor: no answer)");
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

// A small TURN server (RFC 8656 over UDP, long-term credentials) that translates addresses like a
// relay with several sites: each allocation sends from another port than the relayed address it
// reported (Cloudflare anchors each allocation at the nearest data center). Its first ChannelBind or
// CreatePermission gets a 438 (stale nonce) without MESSAGE-INTEGRITY, as servers send it.
class FakeTurn
{
public:
    struct Alloc {
        QHostAddress client;
        quint16 clientPort = 0;
        QUdpSocket *in = nullptr, *out = nullptr;
        QSet<QByteArray> perms;   // per IP address
        QHash<quint16, Stun::Address> chans;
    };
    QUdpSocket main;
    const QString realm = QStringLiteral("test.relay");
    QString nonce = QStringLiteral("nonce-1");
    int stale = 0, channelDataIn = 0, dataOut = 0;
    std::vector<std::unique_ptr<Alloc>> allocs;

    FakeTurn()
    {
        main.bind(QHostAddress(QHostAddress::LocalHost), 0);
        QObject::connect(&main, &QUdpSocket::readyRead, &main, [this] { onMain(); });
    }
    ~FakeTurn()
    {
        for (auto &a : allocs) {
            delete a->in;
            delete a->out;
        }
    }
    quint16 port() const { return main.localPort(); }

    Alloc *find(const QHostAddress &h, quint16 p)
    {
        for (auto &a : allocs)
            if (a->clientPort == p && a->client.isEqual(h, QHostAddress::TolerantConversion))
                return a.get();
        return nullptr;
    }
    void out(Alloc *a, const Stun::Address &peer, const QByteArray &data)
    {
        if (a && a->perms.contains(peer.ip))
            a->out->writeDatagram(data, CallNet::toHost(peer), peer.port);
    }
    void onRelay(Alloc *a, QUdpSocket *s)
    {
        while (s->hasPendingDatagrams()) {
            QByteArray b(int(s->pendingDatagramSize()), 0);
            QHostAddress from;
            quint16 port = 0;
            b.resize(int(s->readDatagram(b.data(), b.size(), &from, &port)));
            const Stun::Address src = CallNet::fromHost(from, port);
            if (!a->perms.contains(src.ip))
                continue;
            quint16 ch = 0;
            for (auto it = a->chans.cbegin(); it != a->chans.cend(); ++it)
                if (it.value() == src)
                    ch = it.key();
            QByteArray msg;
            if (ch) {
                msg = Stun::channelData(ch, b, false);
            } else {
                Stun::Message m;
                m.method = Stun::Data;
                m.cls = Stun::Indication;
                m.tid = Stun::newTid();
                m.addAddress(Stun::XorPeerAddress, src);
                m.add(Stun::DataAttr, b);
                msg = Stun::encode(m);
                ++dataOut;
            }
            main.writeDatagram(msg, a->client, a->clientPort);
        }
    }
    void error(const Stun::Message &req, int code, const QHostAddress &to, quint16 port)
    {
        Stun::Message e;
        e.method = req.method;
        e.cls = Stun::Error;
        e.tid = req.tid;
        QByteArray ec(4, 0);
        ec[2] = char(code / 100);
        ec[3] = char(code % 100);
        e.add(Stun::ErrorCode, ec + "x");
        e.addText(Stun::Realm, realm);
        e.addText(Stun::Nonce, nonce);
        main.writeDatagram(Stun::encode(e), to, port);
    }
    void onMain()
    {
        while (main.hasPendingDatagrams()) {
            QByteArray b(int(main.pendingDatagramSize()), 0);
            QHostAddress from;
            quint16 port = 0;
            b.resize(int(main.readDatagram(b.data(), b.size(), &from, &port)));
            Alloc *a = find(from, port);
            if (Stun::isChannelData(b)) {
                quint16 ch = 0;
                QByteArray data;
                if (a && Stun::parseChannelData(b, &ch, &data) && a->chans.contains(ch)) {
                    ++channelDataIn;
                    out(a, a->chans.value(ch), data);
                }
                continue;
            }
            Stun::Message m;
            if (!Stun::parse(b, &m))
                continue;
            if (m.cls == Stun::Indication && m.method == Stun::Send) {
                out(a, m.address(Stun::XorPeerAddress), m.get(Stun::DataAttr));
                continue;
            }
            if (m.cls != Stun::Request)
                continue;
            const QByteArray key = Stun::longTermKey(m.text(Stun::Username), realm, QStringLiteral("secret"));
            if (!m.has(Stun::MessageIntegrity) || !Stun::checkIntegrity(b, key)) {
                error(m, 401, from, port);
                continue;
            }
            if (m.text(Stun::Nonce) != nonce) {
                error(m, 438, from, port);
                continue;
            }
            if ((m.method == Stun::ChannelBind || m.method == Stun::CreatePermission) && stale == 0) {
                ++stale;
                nonce = QStringLiteral("nonce-2");
                error(m, 438, from, port);
                continue;
            }
            Stun::Message r;
            r.method = m.method;
            r.cls = Stun::Success;
            r.tid = m.tid;
            if (m.method == Stun::Allocate) {
                if (!a) {
                    auto n = std::make_unique<Alloc>();
                    n->client = from;
                    n->clientPort = port;
                    n->in = new QUdpSocket;
                    n->in->bind(QHostAddress(QHostAddress::LocalHost), 0);
                    n->out = new QUdpSocket;
                    n->out->bind(QHostAddress(QHostAddress::LocalHost), 0);
                    a = n.get();
                    for (QUdpSocket *s : {a->in, a->out})
                        QObject::connect(s, &QUdpSocket::readyRead, s, [this, a, s] { onRelay(a, s); });
                    allocs.push_back(std::move(n));
                }
                r.addAddress(Stun::XorRelayedAddress, CallNet::fromHost(QHostAddress(QHostAddress::LocalHost), a->in->localPort()));
                r.addAddress(Stun::XorMappedAddress, CallNet::fromHost(from, port));
                r.addU32(Stun::Lifetime, 600);
            } else if (m.method == Stun::Refresh) {
                r.addU32(Stun::Lifetime, 600);
            } else if (m.method == Stun::CreatePermission || m.method == Stun::ChannelBind) {
                if (!a)
                    continue;
                const Stun::Address peer = m.address(Stun::XorPeerAddress);
                a->perms.insert(peer.ip);
                if (m.method == Stun::ChannelBind) {
                    const QByteArray c = m.get(Stun::ChannelNumber);
                    a->chans.insert(quint16((quint8(c.at(0)) << 8) | quint8(c.at(1))), peer);
                }
            }
            main.writeDatagram(Stun::encode(r, key, true), from, port);
        }
    }
};

// A whole call between two sessions with no Wi-Fi path: everything goes through the relay, which
// translates addresses and lets a nonce go stale.
void relayCall()
{
    std::printf("relay-only call (TURN with address translation)\n");
    FakeTurn turn;
    TurnLogin login;
    login.urls << QStringLiteral("turn:127.0.0.1:%1?transport=udp").arg(turn.port());
    login.username = QStringLiteral("user");
    login.credential = QStringLiteral("secret");
    login.expiresMs = QDateTime::currentMSecsSinceEpoch() + 3600000;
    auto fetch = [login](std::function<void(const TurnLogin &, const QString &)> done) {
        QTimer::singleShot(10, [done, login] { done(login, QString()); });
    };
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
        QPointer<CallSignalHooks> to = other->sig;
        s->sig->onSend = [to, mine](const QString &, const QJsonObject &m) {
            QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
        };
    }
    QObject::connect(b.sig, &CallSignal::received, b.sig, [&b, fetch](const QString &from, const QJsonObject &m) {
        if (b.session) {
            b.session->handle(m);
            return;
        }
        if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer") || !CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
            return;
        b.session = new CallSession(b.sig, from, fetch, b.sig);
        b.session->useDevices = false;
        b.session->takeOffer(m);
        QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
    });
    QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
        if (a.session)
            a.session->handle(m);
    });
    a.session = new CallSession(a.sig, keyB, fetch, a.sig);
    a.session->useDevices = false;
    a.session->start();
    check(waitFor([&] { return a.session && b.session && a.session->state() == CallSession::Active && b.session->state() == CallSession::Active; }, 10000),
          "both sides connected over the relay alone");
    check(a.session && a.session->media() && a.session->media()->live(CallMedia::Relay) && !a.session->media()->live(CallMedia::Wifi), "A: the relay path is live, no Wi-Fi");
    check(b.session && b.session->media() && b.session->media()->live(CallMedia::Relay), "B: the relay path is live");
    if (a.session && b.session && a.session->media() && b.session->media()) {
        CallMedia *ma = a.session->media(), *mb = b.session->media();
        const int inB = mb->stats().audioIn, inA = ma->stats().audioIn;
        qint64 phA = 0, phB = 0;
        for (int i = 0; i < 25; ++i) {
            qint16 pcm[AudioDsp::kFrame];
            AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phA);
            ma->feed(pcm);
            AudioDsp::tone(pcm, AudioDsp::kFrame, 660, 0.4, &phB);
            mb->feed(pcm);
            waitFor([] { return false; }, 20);
        }
        waitFor([&] { return mb->stats().audioIn >= inB + 20 && ma->stats().audioIn >= inA + 20; }, 3000);
        check(mb->stats().audioIn >= inB + 20, "A's audio reaches B through the relay");
        check(ma->stats().audioIn >= inA + 20, "B's audio reaches A through the relay");
        check(turn.dataOut > 0 && ma->relayTargets().size() == 2 && mb->relayTargets().size() == 2,
              "translated sources accepted and answered (advertised and seen address)");
        check(turn.stale == 1 && turn.channelDataIn > 0, "a stale nonce (438 without integrity) doesn't stop the channel");
    }
    if (a.session)
        a.session->hangup();
    waitFor([&] { return !b.session || b.session->state() == CallSession::Ended; }, 3000);
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
    relayCall();
    const bool ok = vectors && stun && jitter && dsp && g_fail == 0;
    std::printf("call self-test: %s\n", ok ? "all good" : "FAILED");
    std::fflush(stdout);
    return ok;
}
