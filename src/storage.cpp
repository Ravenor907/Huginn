#include "storage.h"
#include "places.h"
#include "steam.h"
#include "system.h"

#include <KIO/CopyJob>
#include <KIO/EmptyTrashJob>

#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QUrl>
#include <QtConcurrent>
#include <algorithm>

namespace
{
qint64 dirSize(const QString &path)
{
    if (!QFileInfo(path).isDir())
        return 0;
    qint64 total = 0;
    QDirIterator it(path, QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        it.next();
        total += it.fileInfo().size();
    }
    return total;
}

QVariantMap category(const QString &name, qint64 bytes, const QString &url, const QString &action, const QString &note)
{
    return {{QStringLiteral("name"), name}, {QStringLiteral("bytes"), bytes}, {QStringLiteral("sizeText"), System::formatSize(bytes)},
            {QStringLiteral("url"), url}, {QStringLiteral("action"), action}, {QStringLiteral("note"), note}};
}
}

Storage::Storage(QObject *parent)
    : QObject(parent)
{
    connect(&m_watcher, &QFutureWatcher<QVariantList>::finished, this, [this] {
        m_drives = m_watcher.result();
        Q_EMIT changed();
    });
}

void Storage::refresh()
{
    if (m_watcher.isRunning())
        return;
    m_watcher.setFuture(QtConcurrent::run(&Storage::measure));
    Q_EMIT changed();
}

QVariantList Storage::measure()
{
    const QString home = QDir::homePath();
    QStringList roots{home};
    roots << Places::cardMounts();

    // What each library holds, filed under the drive it lives on.
    struct Bucket { qint64 games = 0, shader = 0, prefixes = 0, roms = 0; QStringList shaderDirs; };
    QHash<QString, Bucket> buckets;
    auto rootOf = [&roots, &home](const QString &path) {
        QString best = home;
        for (const QString &r : roots)
            if (r != home && (path == r || path.startsWith(r + QLatin1Char('/'))))
                best = r;
        return best;
    };
    for (const QString &lib : SteamFiles::libraries()) {
        Bucket &b = buckets[rootOf(lib)];
        QDir apps(lib + QStringLiteral("/steamapps"));
        for (const QString &acf : apps.entryList({QStringLiteral("appmanifest_*.acf")}, QDir::Files)) {
            QFile f(apps.filePath(acf));
            if (f.open(QIODevice::ReadOnly))
                b.games += SteamFiles::parseText(f.readAll()).value(QStringLiteral("AppState")).toMap().value(QStringLiteral("SizeOnDisk")).toLongLong();
        }
        b.shader += dirSize(apps.filePath(QStringLiteral("shadercache")));
        b.shaderDirs << apps.filePath(QStringLiteral("shadercache"));
        b.prefixes += dirSize(apps.filePath(QStringLiteral("compatdata")));
    }
    for (const QString &r : std::as_const(roots))
        buckets[r].roms += dirSize(r + QStringLiteral("/Emulation/roms"));

    QVariantList out;
    for (const QString &r : std::as_const(roots)) {
        const QStorageInfo si(r);
        const bool isHome = r == home;
        const bool card = QString::fromUtf8(si.device()).contains(QLatin1String("mmcblk"));
        const Bucket b = buckets.value(r);
        QVariantList cats;
        cats << category(QStringLiteral("Games"), b.games, QUrl::fromLocalFile(isHome ? SteamFiles::root() + QStringLiteral("/steamapps/common") : r).toString(),
                         QStringLiteral("games"), QStringLiteral("Uninstall games from Steam to free this"));
        cats << category(QStringLiteral("Shader caches"), b.shader, QString(), QStringLiteral("shader"),
                         QStringLiteral("Safe to clear: Steam rebuilds them, with some stutter at first"));
        cats << category(QStringLiteral("Proton prefixes"), b.prefixes, QString(), QStringLiteral("games"),
                         QStringLiteral("Windows game settings and saves. Manage per game in Games"));
        if (b.roms > 0)
            cats << category(QStringLiteral("ROMs"), b.roms, QUrl::fromLocalFile(r + QStringLiteral("/Emulation/roms")).toString(), QStringLiteral("open"), QString());
        if (isHome) {
            const QString dl = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
            cats << category(QStringLiteral("Downloads"), dirSize(dl), QUrl::fromLocalFile(dl).toString(), QStringLiteral("open"),
                             QStringLiteral("Old installers and archives pile up here"));
            cats << category(QStringLiteral("Trash"), dirSize(home + QStringLiteral("/.local/share/Trash")), QStringLiteral("trash:/"),
                             QStringLiteral("trash"), QStringLiteral("Emptying it frees the space for good"));
        }
        std::sort(cats.begin(), cats.end(), [](const QVariant &a, const QVariant &b) {
            return a.toMap().value(QStringLiteral("bytes")).toLongLong() > b.toMap().value(QStringLiteral("bytes")).toLongLong();
        });
        const qint64 total = si.bytesTotal();
        const qint64 freeBytes = si.bytesAvailable();
        out << QVariantMap{{QStringLiteral("name"), isHome ? QStringLiteral("Internal") : card ? QStringLiteral("SD Card") : QFileInfo(r).fileName()},
                           {QStringLiteral("totalText"), System::formatSize(total)},
                           {QStringLiteral("freeText"), System::formatSize(freeBytes)},
                           {QStringLiteral("usedFraction"), total > 0 ? 1.0 - double(freeBytes) / total : 0.0},
                           {QStringLiteral("categories"), cats}};
    }
    return out;
}

void Storage::clearShaderCaches()
{
    QList<QUrl> urls;
    for (const QString &lib : SteamFiles::libraries()) {
        QDir dir(lib + QStringLiteral("/steamapps/shadercache"));
        for (const QString &id : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot))
            urls << QUrl::fromLocalFile(dir.filePath(id));
    }
    if (urls.isEmpty()) {
        Q_EMIT finished(true, QStringLiteral("There are no shader caches to clear."));
        return;
    }
    auto *job = KIO::trash(urls, KIO::HideProgressInfo);
    job->setUiDelegate(nullptr);
    connect(job, &KJob::result, this, [this](KJob *j) {
        Q_EMIT finished(!j->error(), j->error() ? j->errorString() : QStringLiteral("Shader caches moved to the trash. Empty the trash to free the space."));
        refresh();
    });
}

void Storage::emptyTrash()
{
    auto *job = KIO::emptyTrash();
    job->setUiDelegate(nullptr);
    connect(job, &KJob::result, this, [this](KJob *j) {
        Q_EMIT finished(!j->error(), j->error() ? j->errorString() : QStringLiteral("Trash emptied."));
        refresh();
    });
}
