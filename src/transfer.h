#pragma once

#include <QHash>
#include <QHostAddress>
#include <QObject>
#include <QPointer>
#include <QQuickImageProvider>
#include <QTcpServer>
#include <QUrl>
#include <QVariantList>

class QFile;
class QTcpSocket;
class Settings;

// Phone transfer: a tiny web server on the home network, only while the screen is open.
// The phone opens the page (QR code or address), types the 4-digit PIN, and sends files.
// Files picked in Files (Pick files to send) show up on the same page for download.
class TransferServer : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY stateChanged)
    Q_PROPERTY(QString address READ address NOTIFY stateChanged)
    Q_PROPERTY(QString pin READ pin NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    Q_PROPERTY(QVariantList items READ items NOTIFY itemsChanged)
    Q_PROPERTY(QString saveName READ saveName NOTIFY stateChanged)
    Q_PROPERTY(int sharedCount READ sharedCount NOTIFY itemsChanged)
    Q_PROPERTY(QStringList shared READ shared NOTIFY itemsChanged)

public:
    explicit TransferServer(Settings *settings, QObject *parent = nullptr);

    bool running() const { return m_server.isListening(); }
    QString address() const { return m_address; }
    QString pin() const { return m_pin; }
    QString error() const { return m_error; }
    QVariantList items() const;
    QString saveName() const;
    int sharedCount() const { return int(m_shared.size()); }
    QStringList shared() const { return m_shared; }
    Q_INVOKABLE void clearShared() { m_shared.clear(); Q_EMIT itemsChanged(); }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void newPin();
    Q_INVOKABLE void share(const QVariantList &urls);

Q_SIGNALS:
    void stateChanged();
    void itemsChanged();

private:
    struct Incoming {
        QString name;
        qint64 total = 0;
        qint64 received = 0;
        bool failed = false;
        bool done = false;
    };
    struct Conn {
        QByteArray head;
        bool headDone = false;
        QString method, path;
        QHash<QString, QString> query, headers;
        qint64 bodyLeft = 0;
        QFile *file = nullptr;
        int item = -1;
        QFile *sending = nullptr;
    };

    void onConnection();
    void onData(QTcpSocket *s);
    void route(QTcpSocket *s, Conn &c);
    bool authorized(QTcpSocket *s, const Conn &c);
    void reply(QTcpSocket *s, int code, const QByteArray &type, const QByteArray &body, const QByteArray &extraHeaders = {});
    void finishUpload(QTcpSocket *s, Conn &c, bool ok);
    QByteArray page() const;
    QByteArray pinPage(bool wrong) const;
    static QString lanAddress();

    Settings *m_settings;
    QTcpServer m_server;
    QHash<QTcpSocket *, Conn> m_conns;
    QList<Incoming> m_items;
    QStringList m_shared;
    QHash<QString, int> m_failures;   // per phone address, to slow down PIN guessing
    QString m_pin;
    QString m_address;
    QString m_error;
};

// image://qr/<text> draws a QR code for the transfer address.
class QrImageProvider : public QQuickImageProvider
{
public:
    QrImageProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};
