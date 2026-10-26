#include "fileops.h"
#include "archivemodel.h"
#include "foldermodel.h"
#include "settings.h"

#include <KIO/CopyJob>
#include <KIO/FileUndoManager>
#include <KIO/MkdirJob>
#include <KIO/SimpleJob>
#include <KIO/Global>
#include <KArchiveDirectory>
#include <KZip>

#include <QDateTime>
#include <QLocale>
#include <QDir>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QDirIterator>
#include <QStorageInfo>
#include <QStandardPaths>
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
    for (const QUrl &u : urls) {
        if (!u.isLocalFile())
            continue;
        const QString p = QFileInfo(u.toLocalFile()).absoluteFilePath();
        if (p == QDir::homePath() || QStorageInfo(p).rootPath() == p) {
            Q_EMIT finished(false, QStringLiteral("Huginn won't %1 your home folder or a whole drive. To wipe a drive, use Format in USB Tools.").arg(verb));
            return false;
        }
    }
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
        const QString msg = ok ? done : FolderModel::friendlyError(j->error(), j->errorString());
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
    checkRoomThen(urls, dest, false, [this, urls, dest] {
        auto *job = KIO::copy(urls, dest, KIO::HideProgressInfo);
        job->setAutoRename(true);
        KIO::FileUndoManager::self()->recordCopyJob(job);
        const QString what = urls.size() == 1 ? urls.first().fileName() : QStringLiteral("%1 items").arg(urls.size());
        track(job, QStringLiteral("Copying %1…").arg(what), QStringLiteral("Copied %1. Changed your mind? Undo is in the Y menu.").arg(what));
    });
}

void FileOps::move(const QVariantList &list, const QUrl &dest)
{
    const QList<QUrl> urls = toUrls(list);
    if (!guard(urls, QStringLiteral("move")))
        return;
    checkRoomThen(urls, dest, true, [this, urls, dest] {
        auto *job = KIO::move(urls, dest, KIO::HideProgressInfo);
        job->setAutoRename(true);
        KIO::FileUndoManager::self()->recordCopyJob(job);
        const QString what = urls.size() == 1 ? urls.first().fileName() : QStringLiteral("%1 items").arg(urls.size());
        track(job, QStringLiteral("Moving %1…").arg(what), QStringLiteral("Moved %1. Wrong place? Undo is in the Y menu.").arg(what));
    });
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
    track(job, QStringLiteral("Renaming…"), QStringLiteral("Renamed to %1. Undo is in the Y menu.").arg(newName));
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

void FileOps::extract(const QUrl &archive, const QUrl &destDir)
{
    if (busy() || !archive.isLocalFile())
        return;
    const QString src = archive.toLocalFile();
    QString base = archive.fileName();
    for (const char *ext : {".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tgz", ".txz", ".zip", ".7z", ".tar"})
        if (base.endsWith(QLatin1String(ext), Qt::CaseInsensitive)) { base.chop(int(qstrlen(ext))); break; }
    const QString destRoot = destDir.toLocalFile();
    m_status = QStringLiteral("Extracting %1…").arg(archive.fileName());
    m_progress = 0;
    m_compressing = true;
    Q_EMIT busyChanged();
    Q_EMIT progressChanged();
    using Result = QPair<QString, QString>;   // error, where it went
    auto *watcher = new QFutureWatcher<Result>(this);
    connect(watcher, &QFutureWatcher<Result>::finished, this, [this, watcher, destRoot] {
        const Result r = watcher->result();
        if (r.first.isEmpty() && r.second != destRoot) {
            watcher->deleteLater();
            m_compressing = false;
            m_status.clear();
            Q_EMIT busyChanged();
            Q_EMIT progressChanged();
            Q_EMIT finished(true, QStringLiteral("Some of these files were already there, so they went into a new folder, %1. Nothing was replaced.").arg(QFileInfo(r.second).fileName()));
            return;
        }
        watcher->deleteLater();
        m_compressing = false;
        m_status.clear();
        Q_EMIT busyChanged();
        Q_EMIT progressChanged();
        Q_EMIT finished(r.first.isEmpty(), r.first.isEmpty() ? QStringLiteral("Extracted to %1").arg(QFileInfo(r.second).fileName()) : r.first);
    });
    watcher->setFuture(QtConcurrent::run([src, destRoot, base]() -> Result {
        auto a = ArchiveModel::openArchive(src);
        if (!a)
            return {QStringLiteral("Huginn can't read this archive. It may be damaged, or it's a rar (not supported)."), {}};
        // Never overwrite: if anything at the archive's top level is already there, use a new folder.
        QString dest = destRoot;
        const QStringList top = a->directory()->entries();
        for (const QString &e : top) {
            if (QFileInfo::exists(QDir(destRoot).filePath(e))) {
                dest = FileOps::freePath(destRoot, base);
                break;
            }
        }
        if (!QDir().mkpath(dest))
            return {QStringLiteral("Couldn't open the destination folder."), {}};
        if (!a->directory()->copyTo(dest, true))
            return {QStringLiteral("Extracting stopped partway. Is there enough free space?"), {}};
        return {QString(), dest};
    }));
}

QString FileOps::freePath(const QString &dir, const QString &name)
{
    QString path = QDir(dir).filePath(name);
    for (int n = 2; QFileInfo::exists(path); ++n)
        path = QDir(dir).filePath(QStringLiteral("%1 (%2)").arg(name).arg(n));
    return path;
}

QVariantMap FileOps::deleteSize(const QVariantList &list) const
{
    // Count until it's clearly big, so a huge folder never stalls the screen.
    constexpr int kManyFiles = 1000;
    constexpr qint64 kManyBytes = 10LL * 1024 * 1024 * 1024;
    qint64 bytes = 0;
    int files = 0;
    for (const QUrl &u : toUrls(list)) {
        if (!u.isLocalFile())
            continue;
        const QFileInfo fi(u.toLocalFile());
        if (!fi.isDir() || fi.isSymLink()) {
            bytes += fi.size();
            ++files;
            continue;
        }
        QDirIterator it(fi.absoluteFilePath(), QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
        while (it.hasNext() && files < kManyFiles && bytes < kManyBytes) {
            it.next();
            bytes += it.fileInfo().size();
            ++files;
        }
    }
    const bool manyFiles = files >= kManyFiles;
    const bool manyBytes = bytes >= kManyBytes;
    QString text;
    if (manyFiles)
        text = QStringLiteral("over %1 files").arg(QLocale(QLocale::English).toString(kManyFiles));
    else
        text = QStringLiteral("%1 files").arg(files);
    if (manyBytes)
        text += QStringLiteral(", over 10 GB");
    return {{QStringLiteral("big"), manyFiles || manyBytes}, {QStringLiteral("text"), text}};
}

void FileOps::checkRoomThen(const QList<QUrl> &urls, const QUrl &dest, bool move, const std::function<void()> &start)
{
    if (!dest.isLocalFile()) {
        start();
        return;
    }
    const QString destPath = dest.toLocalFile();
    QStringList sources;
    for (const QUrl &u : urls) {
        if (!u.isLocalFile())
            continue;
        // A move on the same drive just relinks the files: it needs no room.
        if (move && QStorageInfo(u.toLocalFile()).device() == QStorageInfo(destPath).device())
            continue;
        sources << u.toLocalFile();
    }
    if (sources.isEmpty()) {
        start();
        return;
    }
    m_status = QStringLiteral("Checking there's room…");
    m_compressing = true;
    Q_EMIT busyChanged();
    Q_EMIT progressChanged();
    struct Need { qint64 total = 0; qint64 biggest = 0; QString biggestName; };
    auto *watcher = new QFutureWatcher<Need>(this);
    connect(watcher, &QFutureWatcher<Need>::finished, this, [this, watcher, destPath, start] {
        const Need need = watcher->result();
        watcher->deleteLater();
        m_compressing = false;
        m_status.clear();
        Q_EMIT busyChanged();
        Q_EMIT progressChanged();
        const QStorageInfo si(destPath);
        const QString fs = QString::fromUtf8(si.fileSystemType()).toLower();
        const bool fat = fs == QLatin1String("vfat") || fs == QLatin1String("msdos") || fs == QLatin1String("fat") || fs == QLatin1String("fat32");
        constexpr qint64 kFatLimit = 4LL * 1024 * 1024 * 1024 - 1;
        if (fat && need.biggest > kFatLimit) {
            Q_EMIT finished(false, QStringLiteral("%1 is bigger than 4 GB, and this drive is FAT32, which can't hold files that big. "
                                                  "Format it in USB Tools (it becomes exFAT) and try again.").arg(need.biggestName));
            return;
        }
        constexpr qint64 kSpare = 64LL * 1024 * 1024;
        if (si.isValid() && need.total + kSpare > si.bytesAvailable()) {
            Q_EMIT finished(false, QStringLiteral("Not enough room there: this needs %1 and only %2 is free. Nothing was changed.")
                                       .arg(KIO::convertSize(need.total), KIO::convertSize(si.bytesAvailable())));
            return;
        }
        start();
    });
    watcher->setFuture(QtConcurrent::run([sources]() {
        Need n;
        auto add = [&n](const QFileInfo &fi) {
            n.total += fi.size();
            if (fi.size() > n.biggest) { n.biggest = fi.size(); n.biggestName = fi.fileName(); }
        };
        for (const QString &s : sources) {
            const QFileInfo fi(s);
            if (!fi.isDir()) { add(fi); continue; }
            QDirIterator it(s, QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
            while (it.hasNext()) { it.next(); add(it.fileInfo()); }
        }
        return n;
    }));
}
