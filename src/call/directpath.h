#pragma once

#include "stun.h"

#include <QHostAddress>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QTimer>

class QUdpSocket;

// The direct internet path between trusted contacts (docs/protocol-0.6.md 5.8.2). Only ever used when
// both sides trust each other: each side learns the public address its call socket has (one STUN
// Binding to stun.cloudflare.com, the server reflexive or "srflx" candidate) and its global IPv6
// addresses ("host" candidates), sends them in the offer or answer, and then both ping each other's
// candidates from the call socket (CallMedia's Direct path), which opens both NATs (hole punching).
// Anyone who isn't trusted never gets these: their calls stay relay only and nobody's IP is revealed.
// Nothing here is ever shown on screen.
namespace DirectPath
{
extern const char kStunHost[];      // "stun.cloudflare.com"
const quint16 kStunPort = 3478;
const int kMaxHost = 2;             // global IPv6 addresses sent at most

// Global unicast IPv6 addresses (2000::/3) of the interfaces that are up, at most kMaxHost.
QList<QHostAddress> globalIpv6();
// True for addresses that never leave the local network: private and shared IPv4, loopback,
// link-local, unique-local IPv6. A "srflx" or "host" candidate is never one of these.
bool isLocal(const QHostAddress &a);
// Self-test only: loopback addresses count as internet addresses, so two sessions on one computer
// can go "direct" through a fake STUN server.
extern bool g_testLocal;
inline bool usable(const QHostAddress &a) { return g_testLocal || !isLocal(a); }
}

// Asks a STUN server what address the call socket has on the internet (RFC 8489 Binding, no
// authentication), through the socket it is given. Datagrams that reach the socket are handed in with
// take(); it keeps only the server's answer to its own transaction. Tries at 0, 300 and 900 ms.
class StunProbe : public QObject
{
    Q_OBJECT
public:
    StunProbe(QUdpSocket *socket, QObject *parent = nullptr);
    void start(const QString &host = QString::fromLatin1(DirectPath::kStunHost), quint16 port = DirectPath::kStunPort);
    // A datagram from the call socket that isn't media. True when it was the answer (or another STUN
    // message from the server) and is used up.
    bool take(const QByteArray &bytes, const QHostAddress &from, quint16 port);
    void setSocket(QUdpSocket *socket) { m_socket = socket; }
    Stun::Address mapped() const { return m_mapped; }
    bool done() const { return m_done; }

Q_SIGNALS:
    void finished();   // mapped() is valid, or there was no answer

private:
    void sendOnce();

    QPointer<QUdpSocket> m_socket;
    QHostAddress m_server;
    quint16 m_port = 0;
    QByteArray m_tid;
    Stun::Address m_mapped;
    int m_tries = 0;
    bool m_done = false;
    QTimer m_retry, m_giveUp;
};
