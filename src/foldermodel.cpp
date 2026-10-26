#include "foldermodel.h"
#include "system.h"

#include <KIO/Global>
#include <KIO/ListJob>

#include <QCollator>
#include <QDir>
#include <QLocale>
#include <QMimeDatabase>
#include <QSet>
#include <algorithm>

FolderModel::FolderModel(QObject *parent)
    : QAbstractListModel(parent)
{
}

int FolderModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : int(m_visible.size());
}

QHash<int, QByteArray> FolderModel::roleNames() const
{
    return {{NameRole, "name"}, {UrlRole, "url"}, {IsDirRole, "isDir"}, {MetaRole, "meta"},
            {KindRole, "kind"}, {SelectedRole, "selected"}, {HiddenRole, "hidden"}};
}

QVariant FolderModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_visible.size())
        return {};
    const Item &it = m_all.at(m_visible.at(index.row()));
    switch (role) {
    case NameRole: return it.name;
    case UrlRole: return it.url;
    case IsDirRole: return it.isDir;
    case KindRole: return it.kind;
    case SelectedRole: return it.selected;
    case HiddenRole: return it.hidden;
    case MetaRole:
        if (it.isDir)
            return QStringLiteral("folder");
        return it.size >= 0 ? System::formatSize(it.size) : QString();
    }
    return {};
}

QString FolderModel::kindFor(const QString &name, const QString &mime, bool isDir)
{
    if (isDir)
        return QStringLiteral("folder");
    const QString n = name.toLower();
    static const QStringList archives = {".zip", ".7z", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2", ".tar.zst"};
    for (const QString &a : archives)
        if (n.endsWith(a))
            return QStringLiteral("archive");
    if (n.endsWith(".iso") || n.endsWith(".img") || n.endsWith(".img.xz") || n.endsWith(".img.bz2") || n.endsWith(".img.gz"))
        return QStringLiteral("disc");
    QString m = mime;
    if (m.isEmpty() || m == QLatin1String("application/octet-stream"))
        m = QMimeDatabase().mimeTypeForFile(name, QMimeDatabase::MatchExtension).name();
    if (m.startsWith(QLatin1String("image/")))
        return QStringLiteral("image");
    if (m.startsWith(QLatin1String("video/")))
        return QStringLiteral("video");
    if (m.startsWith(QLatin1String("audio/")))
        return QStringLiteral("audio");
    if (m.startsWith(QLatin1String("text/")) || m == QLatin1String("application/json") || m == QLatin1String("application/xml")
        || m == QLatin1String("application/x-shellscript") || n.endsWith(".vdf") || n.endsWith(".acf") || n.endsWith(".cfg")
        || n.endsWith(".ini") || n.endsWith(".log") || n.endsWith(".conf") || n.endsWith(".md") || n.endsWith(".yml"))
        return QStringLiteral("text");
    return QStringLiteral("file");
}

QString FolderModel::title() const
{
    return System::displayPath(m_url);
}

QString FolderModel::summary() const
{
    if (!m_error.isEmpty())
        return {};
    int dirs = 0;
    for (int i : m_visible)
        if (m_all.at(i).isDir)
            ++dirs;
    const int files = int(m_visible.size()) - dirs;
    QStringList parts;
    if (dirs)
        parts << (dirs == 1 ? QStringLiteral("1 folder") : QStringLiteral("%1 folders").arg(dirs));
    if (files)
        parts << (files == 1 ? QStringLiteral("1 file") : QStringLiteral("%1 files").arg(files));
    return parts.isEmpty() ? QStringLiteral("Empty") : parts.join(QStringLiteral(", "));
}

void FolderModel::setShowHidden(bool v)
{
    if (m_showHidden == v)
        return;
    m_showHidden = v;
    Q_EMIT showHiddenChanged();
    rebuild();
}

void FolderModel::setFilter(const QString &f)
{
    if (m_filter == f)
        return;
    m_filter = f;
    Q_EMIT filterChanged();
    rebuild();
}

void FolderModel::setUrl(const QUrl &url)
{
    QUrl u = url;
    if (u.isEmpty())
        return;
    if (u.isRelative())
        u = QUrl::fromLocalFile(u.toString());
    if (m_job)
        m_job->kill(KJob::Quietly);
    const bool same = u.adjusted(QUrl::StripTrailingSlash) == m_url.adjusted(QUrl::StripTrailingSlash);
    m_url = u;
    if (!same) {
        m_filter.clear();
        Q_EMIT filterChanged();
    }
    Q_EMIT urlChanged();
    m_error.clear();
    m_incoming.clear();

    m_job = KIO::listDir(m_url, KIO::HideProgressInfo);
    m_job->setUiDelegate(nullptr);
    connect(m_job, &KIO::ListJob::entries, this, &FolderModel::onEntries);
    connect(m_job, &KJob::result, this, &FolderModel::onResult);
    Q_EMIT loadingChanged();
}

void FolderModel::up()
{
    const QUrl parent = KIO::upUrl(m_url);
    if (parent == m_url || parent.isEmpty())
        return;
    m_cameFrom = m_url.adjusted(QUrl::StripTrailingSlash).fileName();
    setUrl(parent);
}

void FolderModel::refresh()
{
    m_cameFrom.clear();
    setUrl(m_url);
}

void FolderModel::onEntries(KIO::Job *, const KIO::UDSEntryList &list)
{
    for (const KIO::UDSEntry &e : list) {
        const QString name = e.stringValue(KIO::UDSEntry::UDS_NAME);
        if (name == QLatin1String(".") || name == QLatin1String(".."))
            continue;
        Item it;
        it.name = e.stringValue(KIO::UDSEntry::UDS_DISPLAY_NAME);
        if (it.name.isEmpty())
            it.name = name;
        const QString target = e.stringValue(KIO::UDSEntry::UDS_URL);
        if (!target.isEmpty()) {
            it.url = QUrl(target);
        } else {
            it.url = m_url;
            it.url.setPath(QDir(m_url.path()).filePath(name));
        }
        it.isDir = e.isDir();
        it.hidden = name.startsWith(QLatin1Char('.')) || e.numberValue(KIO::UDSEntry::UDS_HIDDEN, 0) == 1;
        it.size = it.isDir ? -1 : e.numberValue(KIO::UDSEntry::UDS_SIZE, -1);
        it.mtime = e.numberValue(KIO::UDSEntry::UDS_MODIFICATION_TIME, 0);
        it.mime = e.stringValue(KIO::UDSEntry::UDS_MIME_TYPE);
        it.kind = kindFor(name, it.mime, it.isDir);
        m_incoming.append(it);
    }
}

void FolderModel::onResult(KJob *job)
{
    if (job != m_job)
        return;
    if (job->error()) {
        m_error = job->errorString();
        m_incoming.clear();
    }
    m_job.clear();

    QCollator coll;
    coll.setNumericMode(true);
    coll.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(m_incoming.begin(), m_incoming.end(), [&](const Item &a, const Item &b) {
        if (a.isDir != b.isDir)
            return a.isDir;
        return coll.compare(a.name, b.name) < 0;
    });
    // Keep selections when the same folder is refreshed.
    QSet<QString> keep;
    for (const Item &it : std::as_const(m_all))
        if (it.selected)
            keep.insert(it.url.toString());
    m_all = m_incoming;
    m_incoming.clear();
    for (Item &it : m_all)
        it.selected = keep.contains(it.url.toString());

    rebuild(m_cameFrom);
    m_cameFrom.clear();
    Q_EMIT loadingChanged();
    Q_EMIT selectionChanged();
}

void FolderModel::rebuild(const QString &keepName)
{
    beginResetModel();
    m_visible.clear();
    for (int i = 0; i < m_all.size(); ++i) {
        const Item &it = m_all.at(i);
        if (it.hidden && !m_showHidden)
            continue;
        if (!m_filter.isEmpty() && !it.name.contains(m_filter, Qt::CaseInsensitive))
            continue;
        m_visible.append(i);
    }
    endResetModel();
    Q_EMIT countChanged();
    if (!keepName.isEmpty()) {
        for (int r = 0; r < m_visible.size(); ++r) {
            if (m_all.at(m_visible.at(r)).name == keepName) {
                Q_EMIT restoreIndex(r);
                break;
            }
        }
    }
}

void FolderModel::toggleSelected(int row)
{
    if (row < 0 || row >= m_visible.size())
        return;
    Item &it = m_all[m_visible.at(row)];
    it.selected = !it.selected;
    const QModelIndex i = index(row);
    Q_EMIT dataChanged(i, i, {SelectedRole});
    Q_EMIT selectionChanged();
}

void FolderModel::clearSelection()
{
    for (Item &it : m_all)
        it.selected = false;
    if (!m_visible.isEmpty())
        Q_EMIT dataChanged(index(0), index(int(m_visible.size()) - 1), {SelectedRole});
    Q_EMIT selectionChanged();
}

int FolderModel::selectedCount() const
{
    int n = 0;
    for (const Item &it : m_all)
        if (it.selected)
            ++n;
    return n;
}

QString FolderModel::selectedSize() const
{
    qint64 total = 0;
    int folders = 0;
    for (const Item &it : m_all) {
        if (!it.selected)
            continue;
        if (it.isDir)
            ++folders;
        else if (it.size > 0)
            total += it.size;
    }
    if (folders && total == 0)
        return folders == 1 ? QStringLiteral("1 folder") : QStringLiteral("%1 folders").arg(folders);
    return System::formatSize(total) + (folders ? QStringLiteral(" + folders") : QString());
}

QVariantList FolderModel::selectedUrls() const
{
    QVariantList out;
    for (const Item &it : m_all)
        if (it.selected)
            out << it.url;
    return out;
}

QStringList FolderModel::selectedNames() const
{
    QStringList out;
    for (const Item &it : m_all)
        if (it.selected)
            out << it.name;
    return out;
}

QUrl FolderModel::urlAt(int row) const
{
    return row >= 0 && row < m_visible.size() ? m_all.at(m_visible.at(row)).url : QUrl();
}

QString FolderModel::nameAt(int row) const
{
    return row >= 0 && row < m_visible.size() ? m_all.at(m_visible.at(row)).name : QString();
}

QString FolderModel::kindAt(int row) const
{
    return row >= 0 && row < m_visible.size() ? m_all.at(m_visible.at(row)).kind : QString();
}

bool FolderModel::isDirAt(int row) const
{
    return row >= 0 && row < m_visible.size() && m_all.at(m_visible.at(row)).isDir;
}
