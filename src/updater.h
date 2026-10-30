#pragma once

#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QObject>
#include <QUrl>

class Settings;

// Ally Hub-style updates from GitHub releases (tagged v0.3, v0.31...). Updates install the
// release's Flatpak bundle for this user, then Huginn restarts. Only a file that matches the release's
// SHA-256 and carries the project's signature is installed.
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
    // True when sig is an Ed25519 signature (RFC 8032) of data by publicKey (32 bytes). Every release file is
    // signed with the project's release key (docs/releases.md); Huginn installs nothing without it.
    static bool signedBy(const QByteArray &data, const QByteArray &sig, const QByteArray &publicKey);
    static bool signedByProject(const QByteArray &data, const QByteArray &sig);
    // Checks signedBy() against tests/release-sig-vectors.json (for CI).
    static bool selfTest(const QString &vectorsPath);

Q_SIGNALS:
    void changed();

private:
    void fetch(bool installAfter, bool autoMode = false);
    // GitHub's API limits each address to 60 questions an hour: when it refuses, the "latest release" page (a redirect to the tag) says the same thing.
    void fetchLatestPage(bool installAfter, bool autoMode);
    void chosen(const QString &ver, const QString &tag, const QString &asset, bool installAfter, bool autoMode);
    QNetworkRequest request(const QUrl &url) const;
    void download(const QString &url, const QString &tag);
    // Checks the download against the release's SHA-256 (GitHub's digest or the .sha256 file) and the project's
    // signature (the .sig file beside it), then installs it.
    void verifyThenInstall(const QString &path, const QString &tag);
    void checkThenInstall(const QString &path, const QByteArray &expected, const QByteArray &sig);
    void installFlatpak(const QString &path);
    void failed(const QString &status);
    Settings *m_settings;
    QNetworkAccessManager m_net;
    QString m_current;
    QString m_latest;
    QString m_latestTag;
    QString m_assetUrl;
    QByteArray m_assetDigest;   // SHA-256 (hex) of m_assetUrl's file, when GitHub said it
    QString m_status;
    bool m_busy = false;
    bool m_auto = false;
};
