#pragma once

#include <QHostAddress>
#include <QList>
#include <QNetworkInterface>
#include <QString>

// The home network (Wi-Fi or a wired LAN), and nothing else. Nearby sharing, Wi-Fi chat and the
// phone page listen only on these addresses: never on a VPN, a cellular link, a container bridge
// or a public address, on Windows and Linux alike.
namespace Lan
{
// 10/8, 172.16/12, 192.168/16, fe80::/10 and fd00::/8.
bool isPrivate(const QHostAddress &a);
// Up, not loopback or point-to-point, an Ethernet or Wi-Fi kind of interface whose name doesn't
// say VPN, tunnel, cellular, container or virtual machine.
bool isLanInterface(const QNetworkInterface &iface);
// Every private address on a LAN interface, IPv4 first (link-local IPv6 keeps its scope).
QList<QHostAddress> addresses();
// The first private IPv4 address on a LAN interface (what goes in the QR code and the scan); empty if none.
QString firstIPv4();
// True when the address is inside one of the LAN interfaces' own subnets: someone on the same Wi-Fi.
bool isNeighbour(const QHostAddress &peer);
// The LAN interfaces themselves (for multicast).
QList<QNetworkInterface> interfaces();
// Checks the address and interface rules (for CI); prints what it found.
bool selfTest();
}
