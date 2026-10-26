#pragma once

#include <QNetworkAccessManager>
#include <QObject>

class Settings;

// Ally Hub-style updates from GitHub releases (tagged v0.3, v0.31...). Updates install the
// release's Flatpak bundle for this user, then Huginn restarts.
class Updater : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool available READ available NOTIFY changed)
    Q_PROPERTY(QString latest READ latest NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool autoUpdating READ autoUpdating NOTIFY changed)

public:
    explicit Updater(Settings *settings, QObject *parent = nullptr);

    bool busy() const { return m_busy; }
    bool available() const { return !m_latestTag.isEmpty() && newer(m_latest, m_current); }
    QString latest() const { return m_latest; }
    QString status() const { return m_status; }
    bool autoUpdating() const { return m_auto; }

    // At launch: if a newer release is out, install it and restart.
    void startAutoUpdate();

    Q_INVOKABLE void check();
    Q_INVOKABLE void install();

    static QString versionFromTag(const QString &tag);   // v0.31 -> "0.31"; anything else -> empty
    static bool newer(const QString &a, const QString &b);

Q_SIGNALS:
    void changed();

private:
    void fetch(bool installAfter, bool autoMode = false);
    void download(const QString &url, const QString &tag);
    Settings *m_settings;
    QNetworkAccessManager m_net;
    QString m_current;
    QString m_latest;
    QString m_latestTag;
    QString m_assetUrl;
    QString m_status;
    bool m_busy = false;
    bool m_auto = false;
};
