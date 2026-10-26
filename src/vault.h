#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QObject>
#include <QString>

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
    Q_INVOKABLE void away() { if (!locked()) m_awaySince = QDateTime::currentMSecsSinceEpoch(); }
    Q_INVOKABLE void back() { if (m_awaySince && QDateTime::currentMSecsSinceEpoch() - m_awaySince > 60000) lockScreen(); m_awaySince = 0; }
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

Q_SIGNALS:
    void changed();
    void opened();   // the data key is available (first unlock)

private:
    QString keyFile() const;
    QByteArray m_key;
    bool m_screenLocked = false;
    qint64 m_awaySince = 0;
};
