#pragma once
#include <functional>
#include <memory>
#include <atomic>

#include <QDBusObjectPath>
#include <QObject>
#include <QPointer>
#include <QProcess>
#include <QTimer>
#include <QVariantList>

// USB Tools: finds USB drives through udisks (never the internal drive or the SD card),
// installs and updates Ventoy, writes images, formats and ejects.
//
// Password-free jobs (mount, format, eject) use udisks' normal rules for removable drives.
// Raw jobs (Ventoy, images) need the drive opened byte by byte, which Linux only allows an
// admin to do. The one-time setup adds a polkit rule that allows exactly that, for USB drives
// only, for this user; Huginn then hands the open drive to Handheld Forge's own code.
class UsbManager : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList drives READ drives NOTIFY drivesChanged)
    Q_PROPERTY(QVariantList downloadIsos READ downloadIsos NOTIFY drivesChanged)
    Q_PROPERTY(bool rawReady READ rawReady NOTIFY ruleChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY progressChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(QString status READ status NOTIFY progressChanged)
    Q_PROPERTY(QString log READ log NOTIFY progressChanged)
    Q_PROPERTY(bool failed READ failed NOTIFY progressChanged)
    Q_PROPERTY(bool checking READ checking NOTIFY checkChanged)
    Q_PROPERTY(double checkProgress READ checkProgress NOTIFY checkChanged)

public:
    explicit UsbManager(QObject *parent = nullptr);

    QVariantList drives() const { return m_drives; }
    QVariantList downloadIsos() const { return m_isos; }
    bool rawReady() const;
    bool busy() const { return m_busy; }
    double progress() const { return m_progress; }
    QString status() const { return m_status; }
    QString log() const { return m_log; }
    bool failed() const { return m_failed; }

    Q_INVOKABLE void refresh();
    Q_INVOKABLE void setupRule();
    Q_INVOKABLE void removeRule();
    Q_INVOKABLE void installVentoy(const QString &id, bool secureBoot);
    Q_INVOKABLE void updateVentoy(const QString &id);
    Q_INVOKABLE void writeImage(const QString &id, const QString &imageUrl);
    Q_INVOKABLE void formatDrive(const QString &id);
    Q_INVOKABLE void eject(const QString &id);
    Q_INVOKABLE void cancel();

    // Checking a download before it's written: a .sha256 (or SHA256SUMS) file next to the image
    // gives the expected value. { found, expected, source }.
    Q_INVOKABLE QVariantMap checksumFor(const QString &imageUrl) const;
    Q_INVOKABLE void verifyImage(const QString &imageUrl, const QString &expected);
    Q_INVOKABLE void cancelCheck();

    // What an image is: { windows, label, arm, compressed }. Windows installers can't be copied
    // byte for byte; they're laid out the way Rufus does it (see writeWindows).
    Q_INVOKABLE QVariantMap imageInfo(const QString &imageUrl) const;
    // Rufus-style Windows installer. options: bypass, noOnline, user, regional, noData, noBitlocker.
    Q_INVOKABLE void writeWindows(const QString &id, const QString &isoUrl, const QVariantMap &options);
    // Valve's SteamOS recovery image: downloaded (cached) and written.
    Q_INVOKABLE void writeSteamOS(const QString &id);
    Q_INVOKABLE QString defaultUser() const;
    static QString unattendXml(const QVariantMap &options, bool arm);
    bool checking() const { return m_checking; }
    double checkProgress() const { return m_checkProgress; }

    static QString ruleText(const QString &user);

Q_SIGNALS:
    void drivesChanged();
    void ruleChanged();
    void progressChanged();
    void finished(bool ok, const QString &message);
    void checkChanged();
    void checked(bool ok, const QString &message);

private:
    struct Part { QString block; QString label; QString type; QStringList mounts; };
    struct Drive { QString id; QString block; QString title; QString model; qint64 size = 0; QList<Part> parts; };

    QList<Drive> scan(QString *error) const;
    const Drive *find(const QString &id) const;
    bool unmountAll(const Drive &d, QString *error);
    int openDevice(const QString &block, QString *error);
    void runHelper(const Drive &d, const QStringList &args, const QString &doneText);
    // Runs after the helper instead of finishing the job (ok, error text).
    std::function<void(bool, const QString &)> m_then;
    void windowsCopy(const QString &id, const QString &loop, const QString &isoDir, const QVariantMap &options, bool split, bool arm);
    void finishWindows(const QString &loop, const QString &isoDir);
    std::shared_ptr<std::atomic<qint64>> m_jobDone;
    std::shared_ptr<std::atomic<qint64>> m_jobTotal;
    QTimer m_jobTimer;
    void begin(const QString &status);
    void end(bool ok, const QString &message);
    QString ensureMounted(const Part &p) const;
    void readVentoyInfo(const Part &efi, QString *version, bool *secureBoot) const;

    QList<Drive> m_found;
    QVariantList m_drives;
    QVariantList m_isos;
    QPointer<QProcess> m_helper;
    int m_fd = -1;
    QString m_doneText;
    QString m_helperError;
    bool m_busy = false;
    bool m_checking = false;
    double m_checkProgress = 0;
    std::shared_ptr<std::atomic<qint64>> m_checkDone;
    std::shared_ptr<std::atomic<bool>> m_checkStop;
    QTimer m_checkTimer;
    bool m_failed = false;
    double m_progress = 0;
    QString m_status;
    QString m_log;
    QTimer m_debounce;
};
