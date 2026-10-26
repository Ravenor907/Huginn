#include "archivemodel.h"
#include "foldermodel.h"
#include "system.h"

#include <K7Zip>
#include <KArchive>
#include <KArchiveDirectory>
#include <KArchiveFile>
#include <KTar>
#include <KZip>

#include <QCollator>
#include <QDir>
#include <QFileInfo>
#include <QStandardPaths>
#include <QtConcurrent>
#include <algorithm>

namespace
{
qint64 treeSize(const KArchiveDirectory *dir)
{
    qint64 total = 0;
    for (const QString &name : dir->entries()) {
        const KArchiveEntry *e = dir->entry(name);
        if (e->isDirectory())
            total += treeSize(static_cast<const KArchiveDirectory *>(e));
        else
            total += static_cast<const KArchiveFile *>(e)->size();
    }
    return total;
}
}

ArchiveModel::ArchiveModel(QObject *parent)
    : QAbstractListModel(parent)
{
    connect(&m_watcher, &QFutureWatcher<QString>::finished, this, [this] {
        const QString err = m_watcher.result();
        Q_EMIT busyChanged();
        Q_EMIT finished(err.isEmpty(), err.isEmpty() ? QStringLiteral("Extracted %1").arg(fileName()) : err);
    });
}

ArchiveModel::~ArchiveModel() = default;

std::unique_ptr<KArchive> ArchiveModel::openArchive(const QString &path)
{
    const QString n = path.toLower();
    std::unique_ptr<KArchive> a;
    if (n.endsWith(QLatin1String(".zip")))
        a = std::make_unique<KZip>(path);
    else if (n.endsWith(QLatin1String(".7z")))
        a = std::make_unique<K7Zip>(path);
    else
        a = std::make_unique<KTar>(path);   // .tar and compressed tars; KTar finds the compression
    if (!a->open(QIODevice::ReadOnly))
        return nullptr;
    return a;
}

void ArchiveModel::open(const QUrl &url)
{
    beginResetModel();
    m_url = url;
    m_path.clear();
    m_selected.clear();
    m_error.clear();
    m_archive = openArchive(url.toLocalFile());
    if (!m_archive) {
        m_error = QStringLiteral("Huginn can't read this archive. It may be damaged or use a format it doesn't know (rar isn't supported).");
        m_summary.clear();
    } else {
        const qint64 packed = QFileInfo(url.toLocalFile()).size();
        const qint64 unpacked = treeSize(m_archive->directory());
        m_summary = QStringLiteral("%1 · %2 packed · %3 unpacked")
                        .arg(System::displayPath(url.adjusted(QUrl::RemoveFilename | QUrl::StripTrailingSlash)),
                             System::formatSize(packed), System::formatSize(unpacked));
    }
    load();
    endResetModel();
    Q_EMIT openedChanged();
    Q_EMIT pathChanged();
    Q_EMIT selectionChanged();
}

void ArchiveModel::openSample()
{
    // For the CI tour: a small archive that looks like a homebrew pack.
    const QString path = QDir::tempPath() + QStringLiteral("/homebrew-pack.zip");
    KZip zip(path);
    if (zip.open(QIODevice::WriteOnly)) {
        const QByteArray filler(200000, 'x');
        for (const char *dir : {"apps/file-manager", "apps/media-player", "apps/save-editor", "apps/theme-pack"})
            zip.writeFile(QString::fromLatin1(dir) + QStringLiteral("/data.bin"), filler);
        zip.writeFile(QStringLiteral("apps/install-notes.txt"), QByteArray("Copy each folder to your apps folder.\n"));
        zip.writeFile(QStringLiteral("apps/checksums.sha256"), QByteArray("0000 data.bin\n"));
        zip.close();
    }
    open(QUrl::fromLocalFile(path));
    enter(0);
}

QString ArchiveModel::baseName() const
{
    QString n = fileName();
    for (const char *ext : {".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tgz", ".txz", ".zip", ".7z", ".tar"}) {
        if (n.endsWith(QLatin1String(ext), Qt::CaseInsensitive)) {
            n.chop(int(qstrlen(ext)));
            break;
        }
    }
    return n;
}

QString ArchiveModel::summary() const
{
    int dirs = 0;
    for (const Row &r : m_rows)
        if (r.isDir)
            ++dirs;
    const int files = int(m_rows.size()) - dirs;
    QStringList parts;
    if (dirs)
        parts << (dirs == 1 ? QStringLiteral("1 folder") : QStringLiteral("%1 folders").arg(dirs));
    if (files)
        parts << (files == 1 ? QStringLiteral("1 file") : QStringLiteral("%1 files").arg(files));
    return parts.isEmpty() ? QStringLiteral("Empty") : parts.join(QStringLiteral(", "));
}

QString ArchiveModel::title() const
{
    if (m_path.isEmpty())
        return fileName();
    return fileName() + QStringLiteral(" / ") + m_path.split(QLatin1Char('/'), Qt::SkipEmptyParts).join(QStringLiteral(" / "));
}

void ArchiveModel::load()
{
    m_rows.clear();
    if (!m_archive)
        return;
    const KArchiveDirectory *dir = m_archive->directory();
    if (!m_path.isEmpty()) {
        const KArchiveEntry *e = dir->entry(m_path);
        if (!e || !e->isDirectory())
            return;
        dir = static_cast<const KArchiveDirectory *>(e);
    }
    for (const QString &name : dir->entries()) {
        const KArchiveEntry *e = dir->entry(name);
        const bool isDir = e->isDirectory();
        const qint64 size = isDir ? treeSize(static_cast<const KArchiveDirectory *>(e)) : static_cast<const KArchiveFile *>(e)->size();
        m_rows.append({name, isDir, size});
        m_sizes.insert(m_path.isEmpty() ? name : m_path + QLatin1Char('/') + name, size);
    }
    QCollator coll;
    coll.setNumericMode(true);
    coll.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(m_rows.begin(), m_rows.end(), [&](const Row &a, const Row &b) {
        if (a.isDir != b.isDir)
            return a.isDir;
        return coll.compare(a.name, b.name) < 0;
    });
}

int ArchiveModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : int(m_rows.size());
}

QHash<int, QByteArray> ArchiveModel::roleNames() const
{
    return {{NameRole, "name"}, {IsDirRole, "isDir"}, {MetaRole, "meta"}, {KindRole, "kind"}, {SelectedRole, "selected"}, {HiddenRole, "hidden"}};
}

QVariant ArchiveModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_rows.size())
        return {};
    const Row &r = m_rows.at(index.row());
    const QString full = m_path.isEmpty() ? r.name : m_path + QLatin1Char('/') + r.name;
    switch (role) {
    case NameRole: return r.name;
    case IsDirRole: return r.isDir;
    case MetaRole: return r.isDir ? QStringLiteral("folder") : System::formatSize(r.size);
    case KindRole: return FolderModel::kindFor(r.name, QString(), r.isDir);
    case SelectedRole: return m_selected.contains(full);
    case HiddenRole: return r.name.startsWith(QLatin1Char('.'));
    }
    return {};
}

void ArchiveModel::enter(int row)
{
    if (row < 0 || row >= m_rows.size() || !m_rows.at(row).isDir)
        return;
    beginResetModel();
    m_path = m_path.isEmpty() ? m_rows.at(row).name : m_path + QLatin1Char('/') + m_rows.at(row).name;
    load();
    endResetModel();
    Q_EMIT pathChanged();
}

void ArchiveModel::up()
{
    if (m_path.isEmpty())
        return;
    const int slash = m_path.lastIndexOf(QLatin1Char('/'));
    const QString cameFrom = m_path.mid(slash + 1);
    beginResetModel();
    m_path = slash < 0 ? QString() : m_path.left(slash);
    load();
    endResetModel();
    Q_EMIT pathChanged();
    for (int i = 0; i < m_rows.size(); ++i)
        if (m_rows.at(i).name == cameFrom)
            Q_EMIT restoreIndex(i);
}

bool ArchiveModel::isDirAt(int row) const
{
    return row >= 0 && row < m_rows.size() && m_rows.at(row).isDir;
}

void ArchiveModel::toggleSelected(int row)
{
    if (row < 0 || row >= m_rows.size())
        return;
    const QString full = m_path.isEmpty() ? m_rows.at(row).name : m_path + QLatin1Char('/') + m_rows.at(row).name;
    if (!m_selected.remove(full))
        m_selected.insert(full);
    Q_EMIT dataChanged(index(row), index(row), {SelectedRole});
    Q_EMIT selectionChanged();
}

QString ArchiveModel::selectedSize() const
{
    qint64 total = 0;
    for (const QString &p : m_selected)
        total += m_sizes.value(p);
    return System::formatSize(total);
}

void ArchiveModel::extract(const QUrl &destDir, const QString &subfolder)
{
    if (busy() || !m_archive)
        return;
    QString dest = destDir.toLocalFile();
    if (!subfolder.isEmpty())
        dest = QDir(dest).filePath(subfolder);
    const QString src = m_url.toLocalFile();
    const QStringList picked(m_selected.cbegin(), m_selected.cend());
    m_watcher.setFuture(QtConcurrent::run([src, dest, picked]() -> QString {
        auto a = ArchiveModel::openArchive(src);
        if (!a)
            return QStringLiteral("Couldn't open the archive.");
        if (!QDir().mkpath(dest))
            return QStringLiteral("Couldn't create %1").arg(dest);
        const KArchiveDirectory *root = a->directory();
        if (picked.isEmpty())
            return root->copyTo(dest, true) ? QString() : QStringLiteral("Extracting failed. Is there enough space?");
        for (const QString &p : picked) {
            const KArchiveEntry *e = root->entry(p);
            if (!e)
                continue;
            bool ok;
            if (e->isDirectory()) {
                const QString target = QDir(dest).filePath(e->name());
                ok = QDir().mkpath(target) && static_cast<const KArchiveDirectory *>(e)->copyTo(target, true);
            } else {
                ok = static_cast<const KArchiveFile *>(e)->copyTo(dest);
            }
            if (!ok)
                return QStringLiteral("Couldn't extract %1").arg(e->name());
        }
        return {};
    }));
    Q_EMIT busyChanged();
}
