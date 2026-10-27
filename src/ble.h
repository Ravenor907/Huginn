#pragma once

#include <algorithm>
#include <functional>

#include <QBluetoothDeviceInfo>
#include <QByteArray>
#include <QHash>
#include <QList>
#include <QLowEnergyCharacteristic>
#include <QObject>
#include <QTimer>
#include <QVariantList>

#include "bleframes.h"

class QBluetoothDeviceDiscoveryAgent;
class QBluetoothLocalDevice;
class QLowEnergyController;
class QLowEnergyService;

// Texting Muninn over Bluetooth LE when there is no Wi-Fi or internet.
// Muninn is the peripheral (GATT server); Huginn is only ever the central, because in a Flatpak
// Qt can reach BlueZ over D-Bus for central mode but peripheral mode needs raw HCI access.
//  - Scans for Muninn's service UUID now and every 30 seconds while running.
//  - Connects to a few at once, reads HELLO (who they are), turns on OUTBOX notifications,
//    then sends our own hello as the first message.
//  - Messages are split into frames (bleframes.h) and written one at a time with response.
class Ble : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList peers READ peers NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)
    Q_PROPERTY(bool running READ running NOTIFY changed)

public:
    // hello gives the JSON for our first message on every link:
    // {"t":"hello","name","chatKey","mailbox","app":"huginn"} (an "onion" from a 0.6 app is ignored)
    explicit Ble(std::function<QByteArray()> hello, QObject *parent = nullptr);
    ~Ble() override;

    QVariantList peers() const;   // each {address, name, chatKey, mailbox}
    QString error() const { return m_error; }
    bool running() const { return m_running; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    // Chat just opened (unlocked): introduce ourselves on links made while it was locked.
    void sayHello();

    // Queues a message for the peer with this chatKey. False when it isn't connected or is over 64 KB.
    // True means queued, not delivered.
    bool send(const QString &chatKey, const QByteArray &json);

Q_SIGNALS:
    void changed();
    void message(const QString &chatKey, const QByteArray &json);

private:
    struct Seen {
        QBluetoothDeviceInfo info;
        qint64 seenMs = 0;
        qint64 retryAtMs = 0;
        int failures = 0;
    };
    struct Link {
        quint64 id = 0;
        QString address;
        QObject *ctx = nullptr;   // every signal hookup for this link hangs off this, so one disconnect cuts them all
        QLowEnergyController *ctl = nullptr;
        QLowEnergyService *svc = nullptr;
        QLowEnergyCharacteristic inbox;
        QString name, chatKey, mailbox;
        bool ready = false;
        bool said = false;          // our hello went out: only then can messages use this link
        int mtu = BleFrames::kMinMtu;
        quint32 nextNumber = 0;
        QList<QByteArray> frames;   // waiting to be written, in order
        bool writing = false;
        BleFrames::Reassembler in;
    };

    void scan();
    bool checkAdapter();
    void tick();
    void connectTo(const QString &address);
    void onServiceState(Link *l);
    void onHello(Link *l, const QByteArray &value);
    void onReady(Link *l);
    void queue(Link *l, const QByteArray &json);
    void pump(Link *l);
    void drop(Link *l, bool failed, qint64 retryInMs = -1);
    void dropAll();
    void setError(const QString &e);
    void unusable(const QString &why);
    int linkMtu(Link *l) const;
    // Compares pointers only, so it's safe to ask about a link that was already deleted.
    bool alive(const Link *l) const { return l && std::find(m_links.cbegin(), m_links.cend(), l) != m_links.cend(); }

    std::function<QByteArray()> m_hello;
    QBluetoothDeviceDiscoveryAgent *m_agent = nullptr;
    QBluetoothLocalDevice *m_local = nullptr;
    QTimer m_scanTimer, m_tickTimer;
    QHash<QString, Seen> m_seen;     // Muninn devices heard advertising, by address
    QHash<QString, Link *> m_links;  // connecting or connected, by address
    QString m_error;
    quint64 m_nextId = 0;
    bool m_running = false;
};
