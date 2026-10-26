#pragma once

#include <QObject>
#include <QPointer>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>
#include <functional>

class KJob;
namespace KIO { class CopyJob; }
class Settings;

// Copy, move, trash, rename, new folder, zip and undo. Everything goes through KIO so it works
// the same on the SD card, a USB drive or a network share. Copies never overwrite: a name that
// already exists gets " (1)" added, like Dolphin's "keep both".
class FileOps : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(QString status READ status NOTIFY progressChanged)
    Q_PROPERTY(bool canUndo READ canUndo NOTIFY undoChanged)
    Q_PROPERTY(QString undoText READ undoText NOTIFY undoChanged)

public:
    explicit FileOps(Settings *settings, QObject *parent = nullptr);

    bool busy() const { return m_job != nullptr || m_compressing; }
    double progress() const { return m_progress; }
    QString status() const { return m_status; }
    bool canUndo() const;
    QString undoText() const;

    // Folders Huginn won't delete, move or rename while "Protect game library" is on.
    void setProtectedRoots(const QStringList &roots) { m_protected = roots; }

    Q_INVOKABLE void copy(const QVariantList &urls, const QUrl &dest);
    Q_INVOKABLE void move(const QVariantList &urls, const QUrl &dest);
    Q_INVOKABLE void trash(const QVariantList &urls);
    Q_INVOKABLE void rename(const QUrl &url, const QString &newName);
    Q_INVOKABLE void mkdir(const QUrl &parent, const QString &name);
    Q_INVOKABLE void compress(const QVariantList &urls, const QUrl &destDir, const QString &zipName);
    Q_INVOKABLE void undo();
    Q_INVOKABLE void extract(const QUrl &archive, const QUrl &destDir);
    // How much a delete would remove, counted quickly (stops early): { big, text }.
    Q_INVOKABLE QVariantMap deleteSize(const QVariantList &urls) const;
    // A folder path that doesn't exist yet: "name", then "name (2)", "name (3)"…
    static QString freePath(const QString &dir, const QString &name);

Q_SIGNALS:
    void busyChanged();
    void progressChanged();
    void undoChanged();
    void finished(bool ok, const QString &message);

private:
    static QList<QUrl> toUrls(const QVariantList &list);
    bool guard(const QList<QUrl> &urls, const QString &verb);
    void track(KJob *job, const QString &doing, const QString &done);
    void watchTransfer(KIO::CopyJob *job, const QList<QUrl> &urls, const QUrl &dest, bool move);
    QString transferReport(KJob *job) const;
    void checkRoomThen(const QList<QUrl> &urls, const QUrl &dest, bool move, const std::function<void()> &start);
    static bool validName(const QString &name, QString *why);

    Settings *m_settings;
    QPointer<KJob> m_job;
    bool m_compressing = false;
    double m_progress = 0;
    QString m_status;
    QStringList m_protected;
    struct Report { QPointer<KJob> job; QList<QUrl> items; QList<QUrl> done; QUrl dest; bool move = false; };
    Report m_report;
};
