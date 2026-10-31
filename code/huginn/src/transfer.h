#pragma once

#include <QObject>
#include <QQuickImageProvider>
#include <QStringList>
#include <QVariantList>

class Settings;

// Files picked in Files (Pick files to send), waiting for a device to send them to, and where
// received files are saved.
class Transfer : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString saveName READ saveName NOTIFY stateChanged)
    Q_PROPERTY(int sharedCount READ sharedCount NOTIFY itemsChanged)
    Q_PROPERTY(QStringList shared READ shared NOTIFY itemsChanged)

public:
    explicit Transfer(Settings *settings, QObject *parent = nullptr);

    QString saveName() const;
    int sharedCount() const { return int(m_shared.size()); }
    QStringList shared() const { return m_shared; }
    Q_INVOKABLE void clearShared() { m_shared.clear(); Q_EMIT itemsChanged(); }
    Q_INVOKABLE void share(const QVariantList &urls);

Q_SIGNALS:
    void stateChanged();
    void itemsChanged();

private:
    Settings *m_settings;
    QStringList m_shared;
};

// image://qr/<text> draws a QR code (my code, a group code, Direct connect).
class QrImageProvider : public QQuickImageProvider
{
public:
    QrImageProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};
