#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QObject>
#include <QString>
#include <QTimer>
#include <QVariant>
#include <utility>
#include <vector>

// Everything Huginn's chat keeps (messages, photos, voice, contacts, keys) is encrypted on disk with
// one random 256-bit data key (AES-256-GCM). That key is locked by the person's pattern or password
// (PBKDF2-SHA256, 310,000 rounds), the same scheme Muninn uses. A lock is required: until one is
// set, or while locked, nothing is readable, even by Huginn.
class Vault : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool exists READ exists NOTIFY changed)
    Q_PROPERTY(bool locked READ locked NOTIFY changed)
    Q_PROPERTY(bool usesPattern READ usesPattern NOTIFY changed)
    Q_PROPERTY(int waitSeconds READ waitSeconds NOTIFY changed)

public:
    explicit Vault(QObject *parent = nullptr);

    bool exists() const;          // a lock has been set
    bool locked() const { return m_key.isEmpty(); }
    bool usesPattern() const;
    int waitSeconds() const;      // after too many wrong tries, seconds until the next try

    // Opens with the pattern ("pattern:0,3,6,...") or password. False if wrong.
    Q_INVOKABLE bool unlock(const QString &secret);
    // Sets the lock the first time (creating the data key), or changes it (must be unlocked).
    Q_INVOKABLE bool setSecret(const QString &secret, bool pattern);
    // Shows the lock screen again; the data key stays in memory while Huginn runs, so incoming
    // messages are still saved (encrypted) in the background.
    Q_INVOKABLE void lockScreen() { m_screenLocked = true; Q_EMIT changed(); }
    // Leaving Chat (or Huginn going to the background) starts the clock; back after a minute = locked again.
    // The lock also goes up while still away, once the minute is past, so the window's last picture
    // (shown again on wake before Huginn draws) is already the lock and never the chat.
    Q_INVOKABLE void away() { if (!locked()) { m_awaySince = QDateTime::currentMSecsSinceEpoch(); m_awayTimer.start(); } }
    Q_INVOKABLE void back() { checkAway(); m_awaySince = 0; m_awayTimer.stop(); }
    // Wall clock, so time asleep counts. True when the lock went up.
    bool checkAway() { if (m_awaySince && !m_screenLocked && QDateTime::currentMSecsSinceEpoch() - m_awaySince > 60000) { lockScreen(); return true; } return false; }
    Q_PROPERTY(bool screenLocked READ screenLocked NOTIFY changed)
    bool screenLocked() const { return m_screenLocked || locked(); }
    // Erases every message, photo, voice message, contact and key. Huginn's other settings stay.
    Q_INVOKABLE void eraseAll();

    // Encrypted files in Huginn's data folder. read() returns empty when missing, locked or damaged;
    // older plain files are read once and saved encrypted.
    bool write(const QString &name, const QByteArray &data) const;
    QByteArray read(const QString &name) const;
    void remove(const QString &name) const;
    QString path(const QString &name) const;

    // The math, separate so the self-test can check it.
    static QByteArray seal(const QByteArray &key, const QByteArray &plain);
    static QByteArray open(const QByteArray &key, const QByteArray &data, bool *ok);
    static QByteArray derive(const QString &secret, const QByteArray &salt, int rounds);
    static bool selfTest();

    // ---------------------------------------------------------------- encrypted backup
    // The same file Muninn makes: "MUNB1", a 4-byte big-endian header length, the header JSON
    // {v, salt, rounds, created, app}, then AES-256-GCM (iv, ciphertext, tag, like seal()) of the
    // files one after another: [uint32 BE name length][name][uint32 BE data length][data].
    // Names are paths inside the chat folder; data is each file's plain contents.
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString backupFolder READ backupFolder CONSTANT)
    bool busy() const { return m_busy; }
    QString backupFolder() const;
    // Writes huginn-backup-YYYY-MM-DD.mbk to Documents. Answers with done("backup", ok, message).
    Q_INVOKABLE void backup(const QString &password);
    // The .mbk files in Documents, newest first: [{ name, detail, url }] (url is the full path).
    Q_INVOKABLE QVariantList backups() const;
    // Checks the password and reads the backup into memory; done("open", ok, message). Nothing
    // changes until restoreOpened(); forgetOpened() drops it.
    Q_INVOKABLE void openBackup(const QString &file, const QString &password);
    // Replaces everything in Chat with the backup just opened, then restarts Huginn to load it.
    Q_INVOKABLE void restoreOpened();
    Q_INVOKABLE void forgetOpened();

    using Files = std::vector<std::pair<QString, QByteArray>>;
    // The format, separate so --backup-selftest can run it on a temp folder.
    static QByteArray packBackup(const QString &password, const Files &files, const QString &app, int rounds = 310000);
    // 0 ok, 1 not a backup or damaged, 2 wrong password (or changed), 3 another app's backup.
    static int unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error);
    static bool safeName(const QString &name);
    // Every file in a chat folder, opened with the data key (vault.key itself stays out).
    static Files collect(const QByteArray &key, const QString &dir);
    // Writes the files, sealed with the data key, into a fresh folder next to dir, then swaps
    // them in: everything in dir except vault.key is replaced.
    static bool replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error);
    static bool backupSelfTest();

    // ---------------------------------------------------------------- panic lock
    // Hold L3 and R3 for a second anywhere in Huginn: Chat locks at once. Main.qml passes every
    // controller press and release here.
    Q_PROPERTY(bool panicLock READ panicLock WRITE setPanicLock NOTIFY changed)
    // Lock the moment Huginn stops being the app in front.
    Q_PROPERTY(bool lockOnLeave READ lockOnLeave WRITE setLockOnLeave NOTIFY changed)
    bool panicLock() const;
    void setPanicLock(bool on);
    bool lockOnLeave() const;
    void setLockOnLeave(bool on);
    Q_INVOKABLE void button(const QString &name, bool down);

Q_SIGNALS:
    void changed();
    void opened();   // the data key is available (first unlock)
    void done(const QString &what, bool ok, const QString &message);   // backup, open, restore
    void panicked(); // the panic combo just locked Chat: close anything that could show it

private:
    QString keyFile() const;
    QByteArray m_key;
    bool m_screenLocked = false;
    qint64 m_awaySince = 0;
    bool m_busy = false;
    bool m_restarting = false;   // restored: nothing may be saved until Huginn starts again
    Files m_opened;
    QTimer m_combo;
    QTimer m_awayTimer;
    bool m_l3 = false;
    bool m_r3 = false;
};
