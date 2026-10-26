#include "fileops.h"
#include "settings.h"

#include <KIO/CopyJob>
#include <KIO/FileUndoManager>
#include <KIO/MkdirJob>
#include <KIO/SimpleJob>
#include <KZip>

#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QtConcurrent>

namespace
{
// Undo without desktop dialogs: Huginn already asked before anything destructive.
class QuietUndoUi : public KIO::FileUndoManager::UiInterface
{
public:
    void jobError(KIO::Job *) override {}
    bool copiedFileWasModified(const QUrl &, const QUrl &, const QDateTime &, const QDateTime &) override { return true; }
};
}

FileOps::FileOps(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
{
    auto *undo = KIO::FileUndoManager::self();
    undo->setUiInterface(new QuietUndoUi);
    connect(undo, &KIO::FileUndoManager::undoAvailable, this, &FileOps::undoChanged);
    connect(undo, &KIO::FileUndoManager::undoTextChanged, this, &FileOps::undoChanged);
    connect(undo, &KIO::FileUndoManager::undoJobFinished, this, [this] {
        Q_EMIT undoChanged();
        Q_EMIT finished(true, QStringLiteral("Undone"));
    });
}

bool FileOps::canUndo() const
{
    return KIO::FileUndoManager::self()->isUndoAvailable();
}

QString FileOps::undoText() const
{
    return KIO::FileUndoManager::self()->undoText();
}

QList<QUrl> FileOps::toUrls(const QVariantList &list)
{
    QList<QUrl> out;
    for (const QVariant &v : list) {
        QUrl u = v.toUrl();
        if (u.isEmpty())
            u = QUrl(v.toString());
        if (!u.isEmpty())
            out << u;
    }
    return out;
}

bool FileOps::guard(const QList<QUrl> &urls, const QString &verb)
{
    if (busy()) {
        Q_EMIT finished(false, QStringLiteral("Wait for the current job to finish first."));
        return false;
    }
    if (urls.isEmpty())
        return false;
    if (!m_settings->protectLibrary())
        return true;
    for (const QUrl &u : urls) {
        if (!u.isLocalFile())
            continue;
        const QString p = QFileInfo(u.toLocalFile()).absoluteFilePath();
        for (const QString &root : std::as_const(m_protected)) {
            if (p == root || p.startsWith(root + QLatin1Char('/'))) {
                Q_EMIT finished(false, QStringLiteral("Huginn won't %1 game installs while Protect game library is on (Settings › Safety).").arg(verb));
                return false;
            }
        }
    }
    return true;
}

void FileOps::track(KJob *job, const QString &doing, const QString &done)
{
    m_job = job;
    m_progress = 0;
    m_status = doing;
    job->setUiDelegate(nullptr);
    Q_EMIT busyChanged();
    Q_EMIT progressChanged();
    connect(job, &KJob::percentChanged, this, [this](KJob *, unsigned long pct) {
        m_progress = pct / 100.0;
        Q_EMIT progressChanged();
    });
    connect(job, &KJob::result, this, [this, done](KJob *j) {
        const bool ok = !j->error();
        const QString msg = ok ? done : j->errorString();
        m_job.clear();
        m_progress = 0;
        Q_EMIT busyChanged();
        Q_EMIT progressChanged();
        Q_EMIT undoChanged();
        Q_EMIT finished(ok, msg);
    });
}

void FileOps::copy(const QVariantList &list, const QUrl &dest)
{
    const QList<QUrl> urls = toUrls(list);
    if (urls.isEmpty())
        return;
    if (busy()) {
        Q_EMIT finished(false, QStringLiteral("Wait for the current job to finish first."));
        return;
    }
    auto *job = KIO::copy(urls, dest, KIO::HideProgressInfo);
    job->setAutoRename(true);
    KIO::FileUndoManager::self()->recordCopyJob(job);
    const QString what = urls.size() == 1 ? urls.first().fileName() : QStringLiteral("%1 items").arg(urls.size());
    track(job, QStringLiteral("Copying %1…").arg(what), QStringLiteral("Copied %1").arg(what));
}

void FileOps::move(const QVariantList &list, const QUrl &dest)
{
    const QList<QUrl> urls = toUrls(list);
    if (!guard(urls, QStringLiteral("move")))
        return;
    auto *job = KIO::move(urls, dest, KIO::HideProgressInfo);
    job->setAutoRename(true);
    KIO::FileUndoManager::self()->recordCopyJob(job);
    const QString what = urls.size() == 1 ? urls.first().fileName() : QStringLiteral("%1 items").arg(urls.size());
    track(job, QStringLiteral("Moving %1…").arg(what), QStringLiteral("Moved %1").arg(what));
}

void FileOps::trash(const QVariantList &list)
{
    const QList<QUrl> urls = toUrls(list);
    if (!guard(urls, QStringLiteral("delete")))
        return;
    auto *job = KIO::trash(urls, KIO::HideProgressInfo);
    KIO::FileUndoManager::self()->recordJob(KIO::FileUndoManager::Trash, urls, QUrl(QStringLiteral("trash:/")), job);
    const QString what = urls.size() == 1 ? urls.first().fileName() : QStringLiteral("%1 items").arg(urls.size());
    track(job, QStringLiteral("Moving %1 to the trash…").arg(what), QStringLiteral("Moved %1 to the trash").arg(what));
}

bool FileOps::validName(const QString &name, QString *why)
{
    if (name.isEmpty() || name == QLatin1String(".") || name == QLatin1String("..")) {
        *why = QStringLiteral("That name can't be used.");
        return false;
    }
    if (name.contains(QLatin1Char('/'))) {
        *why = QStringLiteral("Names can't contain a slash.");
        return false;
    }
    return true;
}

void FileOps::rename(const QUrl &url, const QString &newName)
{
    QString why;
    if (!validName(newName, &why)) {
        Q_EMIT finished(false, why);
        return;
    }
    if (!guard({url}, QStringLiteral("rename")))
        return;
    QUrl dest = url.adjusted(QUrl::RemoveFilename | QUrl::StripTrailingSlash);
    dest.setPath(dest.path() + QLatin1Char('/') + newName);
    if (dest == url)
        return;
    auto *job = KIO::moveAs(url, dest, KIO::HideProgressInfo);
    KIO::FileUndoManager::self()->recordJob(KIO::FileUndoManager::Rename, {url}, dest, job);
    track(job, QStringLiteral("Renaming…"), QStringLiteral("Renamed to %1").arg(newName));
}

void FileOps::mkdir(const QUrl &parent, const QString &name)
{
    QString why;
    if (!validName(name, &why)) {
        Q_EMIT finished(false, why);
        return;
    }
    if (busy())
        return;
    QUrl dest = parent;
    dest.setPath(QDir(parent.path()).filePath(name));
    auto *job = KIO::mkdir(dest);
    KIO::FileUndoManager::self()->recordJob(KIO::FileUndoManager::Mkdir, {}, dest, job);
    track(job, QStringLiteral("Creating folder…"), QStringLiteral("Created %1").arg(name));
}

void FileOps::compress(const QVariantList &list, const QUrl &destDir, const QString &zipName)
{
    const QList<QUrl> urls = toUrls(list);
    if (busy() || urls.isEmpty())
        return;
    for (const QUrl &u : urls) {
        if (!u.isLocalFile()) {
            Q_EMIT finished(false, QStringLiteral("Only local files can be zipped for now."));
            return;
        }
    }
    QString name = zipName.endsWith(QLatin1String(".zip"), Qt::CaseInsensitive) ? zipName : zipName + QStringLiteral(".zip");
    QString path = QDir(destDir.toLocalFile()).filePath(name);
    for (int n = 1; QFileInfo::exists(path); ++n)
        path = QDir(destDir.toLocalFile()).filePath(QFileInfo(name).completeBaseName() + QStringLiteral(" (%1).zip").arg(n));

    m_status = QStringLiteral("Compressing…");
    m_progress = 0;
    m_compressing = true;
    auto *watcher = new QFutureWatcher<QString>(this);
    Q_EMIT busyChanged();
    Q_EMIT progressChanged();
    QStringList files;
    for (const QUrl &u : urls)
        files << u.toLocalFile();
    connect(watcher, &QFutureWatcher<QString>::finished, this, [this, watcher, path] {
        const QString err = watcher->result();
        watcher->deleteLater();
        m_status.clear();
        m_compressing = false;
        Q_EMIT busyChanged();
        Q_EMIT progressChanged();
        Q_EMIT finished(err.isEmpty(), err.isEmpty() ? QStringLiteral("Created %1").arg(QFileInfo(path).fileName()) : err);
    });
    watcher->setFuture(QtConcurrent::run([files, path]() -> QString {
        KZip zip(path);
        if (!zip.open(QIODevice::WriteOnly))
            return QStringLiteral("Couldn't create %1").arg(QFileInfo(path).fileName());
        for (const QString &f : files) {
            const QFileInfo fi(f);
            const bool ok = fi.isDir() ? zip.addLocalDirectory(f, fi.fileName()) : zip.addLocalFile(f, fi.fileName());
            if (!ok) {
                zip.close();
                QFile::remove(path);
                return QStringLiteral("Couldn't add %1 to the zip").arg(fi.fileName());
            }
        }
        zip.close();
        return {};
    }));
}

void FileOps::undo()
{
    if (busy() || !canUndo())
        return;
    KIO::FileUndoManager::self()->undo();
}
