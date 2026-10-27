#include "directpath.h"

#include "turnclient.h"

#include <QNetworkInterface>
#include <QUdpSocket>

const char DirectPath::kStunHost[] = "stun.cloudflare.com";
bool DirectPath::g_testLocal = false;

bool DirectPath::isLocal(const QHostAddress &h)
{
    bool v4 = false;
    const quint32 a = h.toIPv4Address(&v4);
    if (v4) {
        const quint32 b0 = a >> 24, b1 = (a >> 16) & 0xFF;
        return b0 == 10 || b0 == 127 || b0 == 0 || (b0 == 172 && (b1 & 0xF0) == 16) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
               || (b0 == 100 && (b1 & 0xC0) == 64) || b0 >= 224;
    }
    if (h.protocol() != QAbstractSocket::IPv6Protocol)
        return true;
    const Q_IPV6ADDR v6 = h.toIPv6Address();
    // Only 2000::/3 is global unicast; everything else (::1, fe80::/10, fc00::/7, ff00::/8) stays home.
    return (v6[0] & 0xE0) != 0x20;
}

QList<QHostAddress> DirectPath::globalIpv6()
{
    QList<QHostAddress> out;
    const auto ifaces = QNetworkInterface::allInterfaces();
    for (const QNetworkInterface &i : ifaces) {
        const auto f = i.flags();
        if (!(f & QNetworkInterface::IsUp) || !(f & QNetworkInterface::IsRunning) || (f & QNetworkInterface::IsLoopBack))
            continue;
        for (const QNetworkAddressEntry &e : i.addressEntries()) {
            QHostAddress a = e.ip();
            if (a.protocol() != QAbstractSocket::IPv6Protocol || isLocal(a))
                continue;
            a.setScopeId(QString());
            if (!out.contains(a))
                out << a;
            if (out.size() >= kMaxHost)
                return out;
        }
    }
    return out;
}

StunProbe::StunProbe(QUdpSocket *socket, QObject *parent) : QObject(parent), m_socket(socket)
{
    m_retry.setSingleShot(true);
    connect(&m_retry, &QTimer::timeout, this, &StunProbe::sendOnce);
    m_giveUp.setSingleShot(true);
    connect(&m_giveUp, &QTimer::timeout, this, [this] {
        if (m_done)
            return;
        m_done = true;
        m_retry.stop();
        Q_EMIT finished();
    });
}

void StunProbe::start(const QString &host, quint16 port)
{
    m_port = port;
    m_tid = Stun::newTid();
    m_giveUp.start(3000);
    CallNet::resolve(host, this, [this](const QHostAddress &a) {
        if (m_done)
            return;
        if (a.isNull()) {
            m_giveUp.stop();
            m_done = true;
            Q_EMIT finished();
            return;
        }
        m_server = a;
        sendOnce();
    }, 2);
}

void StunProbe::sendOnce()
{
    if (m_done || !m_socket || m_server.isNull())
        return;
    Stun::Message m;
    m.method = Stun::Binding;
    m.cls = Stun::Request;
    m.tid = m_tid;
    m_socket->writeDatagram(Stun::encode(m, QByteArray(), true), m_server, m_port);
    static const int kGaps[] = {300, 600, 900};
    if (m_tries < 3)
        m_retry.start(kGaps[m_tries++]);
}

bool StunProbe::take(const QByteArray &bytes, const QHostAddress &from, quint16 port)
{
    if (!Stun::isStun(bytes) || m_server.isNull() || port != m_port || CallNet::fromHost(from, port) != CallNet::fromHost(m_server, m_port))
        return false;
    Stun::Message m;
    if (!Stun::parse(bytes, &m) || m.tid != m_tid)
        return true;   // the server's, but not ours: nothing to do with it
    if (m_done || m.method != Stun::Binding || m.cls != Stun::Success)
        return true;
    Stun::Address a = m.address(Stun::XorMappedAddress);
    if (!a.valid())
        a = Stun::Address();
    // The address the server saw must be a real internet address (a local one is no use to anyone).
    if (a.valid() && !DirectPath::usable(CallNet::toHost(a)))
        a = Stun::Address();
    m_mapped = a;
    m_done = true;
    m_retry.stop();
    m_giveUp.stop();
    Q_EMIT finished();
    return true;
}
