#include "steam.h"
#include "system.h"

#include <KIO/CopyJob>
#include <KZip>

#include <QCollator>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QRegularExpression>
#include <QSet>
#include <QUrl>
#include <QtConcurrent>
#include <algorithm>

namespace
{
qint64 dirSize(const QString &path)
{
    if (!QFileInfo(path).isDir())
        return -1;
    qint64 total = 0;
    QDirIterator it(path, QDir::Files | QDir::Hidden | QDir::System | QDir::NoSymLinks, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        it.next();
        total += it.fileInfo().size();
    }
    return total;
}

QString findArt(const QString &root, const QString &appId)
{
    const QString cache = root + QStringLiteral("/appcache/librarycache/");
    const QStringList candidates = {
        cache + appId + QStringLiteral("_library_600x900.jpg"),
        cache + appId + QStringLiteral("/library_600x900.jpg"),
        cache + appId + QStringLiteral("_icon.jpg"),
    };
    for (const QString &c : candidates)
        if (QFile::exists(c))
            return QUrl::fromLocalFile(c).toString();
    return {};
}

bool isTool(const QString &name)
{
    static const QStringList tools = {QStringLiteral("Proton"), QStringLiteral("Steam Linux Runtime"),
                                      QStringLiteral("Steamworks Common Redistributables"), QStringLiteral("SteamVR")};
    for (const QString &t : tools)
        if (name.startsWith(t))
            return true;
    return false;
}
}

// ------------------------------------------------------------------ Steam's files

QString SteamFiles::root()
{
    const QStringList candidates = {QDir::homePath() + QStringLiteral("/.local/share/Steam"),
                                    QDir::homePath() + QStringLiteral("/.steam/steam"),
                                    QDir::homePath() + QStringLiteral("/.var/app/com.valvesoftware.Steam/data/Steam")};
    for (const QString &c : candidates)
        if (QFileInfo(c + QStringLiteral("/steamapps")).isDir())
            return QFileInfo(c).canonicalFilePath();
    return {};
}

QVariantMap SteamFiles::parseText(const QByteArray &vdf)
{
    // Tokens are quoted strings and braces; keys repeat as nested maps.
    QList<QVariantMap> stack{{}};
    QStringList keys;
    QString pendingKey;
    bool haveKey = false;
    int i = 0;
    const int n = vdf.size();
    auto readString = [&]() {
        QByteArray out;
        ++i;
        while (i < n && vdf[i] != '"') {
            if (vdf[i] == '\\' && i + 1 < n) {
                ++i;
                out += vdf[i] == 'n' ? '\n' : vdf[i];
            } else {
                out += vdf[i];
            }
            ++i;
        }
        ++i;
        return QString::fromUtf8(out);
    };
    while (i < n) {
        const char c = vdf[i];
        if (c == '"') {
            const QString s = readString();
            if (!haveKey) {
                pendingKey = s;
                haveKey = true;
            } else {
                stack.last()[pendingKey] = s;
                haveKey = false;
            }
        } else if (c == '{') {
            stack.append(QVariantMap());
            keys.append(pendingKey);
            haveKey = false;
            ++i;
        } else if (c == '}') {
            if (stack.size() > 1) {
                const QVariantMap done = stack.takeLast();
                stack.last()[keys.takeLast()] = done;
            }
            ++i;
        } else if (c == '/' && i + 1 < n && vdf[i + 1] == '/') {
            while (i < n && vdf[i] != '\n')
                ++i;
        } else {
            ++i;
        }
    }
    return stack.first();
}

QList<QVariantMap> SteamFiles::parseShortcuts(const QByteArray &bin)
{
    // Binary VDF: 0x00 map, 0x01 string, 0x02 int32, 0x08 end of map.
    QList<QVariantMap> out;
    int i = 0;
    const int n = bin.size();
    auto cstr = [&]() {
        const int start = i;
        while (i < n && bin[i] != '\0')
            ++i;
        const QString s = QString::fromUtf8(bin.mid(start, i - start));
        ++i;
        return s;
    };
    int depth = 0;
    QVariantMap current;
    while (i < n) {
        const char type = bin[i++];
        if (type == 0x08) {
            if (depth == 2 && !current.isEmpty()) {
                out.append(current);
                current.clear();
            }
            --depth;
            if (depth < 0)
                break;
            continue;
        }
        const QString key = cstr();
        if (type == 0x00) {
            ++depth;
        } else if (type == 0x01) {
            const QString v = cstr();
            if (depth == 2)
                current[key.toLower()] = v;
        } else if (type == 0x02) {
            if (i + 4 > n)
                break;
            const quint32 v = quint8(bin[i]) | quint8(bin[i + 1]) << 8 | quint8(bin[i + 2]) << 16 | quint32(quint8(bin[i + 3])) << 24;
            i += 4;
            if (depth == 2)
                current[key.toLower()] = QString::number(v);
        } else {
            break;
        }
    }
    return out;
}

QStringList SteamFiles::libraries()
{
    const QString r = root();
    if (r.isEmpty())
        return {};
    QStringList out{r};
    QFile f(r + QStringLiteral("/steamapps/libraryfolders.vdf"));
    if (f.open(QIODevice::ReadOnly)) {
        const QVariantMap top = parseText(f.readAll());
        const QVariantMap folders = top.value(QStringLiteral("libraryfolders")).toMap();
        for (const QVariant &v : folders) {
            const QString path = v.toMap().value(QStringLiteral("path")).toString();
            const QString canon = QFileInfo(path).canonicalFilePath();
            if (!canon.isEmpty() && !out.contains(canon) && QFileInfo(canon + QStringLiteral("/steamapps")).isDir())
                out << canon;
        }
    }
    return out;
}

QString SteamFiles::screenshotsDir()
{
    const QString r = root();
    if (!r.isEmpty()) {
        QDir userdata(r + QStringLiteral("/userdata"));
        for (const QString &id : userdata.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            const QString remote = userdata.filePath(id + QStringLiteral("/760/remote"));
            if (QFileInfo(remote).isDir())
                return remote;
        }
    }
    const QString pics = QDir::homePath() + QStringLiteral("/Pictures/Screenshots");
    return QFileInfo(pics).isDir() ? pics : QDir::homePath() + QStringLiteral("/Pictures");
}

// ------------------------------------------------------------------ the model

GamesModel::GamesModel(QObject *parent)
    : QAbstractListModel(parent)
{
    connect(&m_watcher, &QFutureWatcher<QList<Game>>::finished, this, [this] {
        beginResetModel();
        m_games = m_watcher.result();
        sort();
        endResetModel();
        m_loading = false;
        Q_EMIT loadingChanged();
        Q_EMIT countChanged();
    });
}

int GamesModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : int(m_games.size());
}

QHash<int, QByteArray> GamesModel::roleNames() const
{
    return {{NameRole, "name"}, {AppIdRole, "appId"}, {SizeRole, "sizeText"}, {InitialsRole, "initials"}, {ArtRole, "art"}};
}

QVariant GamesModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_games.size())
        return {};
    const Game &g = m_games.at(index.row());
    switch (role) {
    case NameRole: return g.name;
    case AppIdRole: return g.appId;
    case SizeRole: return System::formatSize(g.total());
    case InitialsRole: return initials(g.name);
    case ArtRole: return g.art;
    }
    return {};
}

QString GamesModel::initials(const QString &name)
{
    QString out;
    const QStringList words = name.split(QRegularExpression(QStringLiteral("[\\s:\\-]+")), Qt::SkipEmptyParts);
    for (const QString &w : words) {
        if (w.front().isLetterOrNumber())
            out += w.front().toUpper();
        if (out.size() == 2)
            break;
    }
    if (out.size() == 1 && name.size() > 1)
        out += name.at(1).toUpper();
    return out;
}

void GamesModel::setSortBySize(bool v)
{
    if (v == m_sortBySize)
        return;
    m_sortBySize = v;
    beginResetModel();
    sort();
    endResetModel();
    Q_EMIT sortChanged();
}

void GamesModel::sort()
{
    QCollator coll;
    coll.setNumericMode(true);
    coll.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(m_games.begin(), m_games.end(), [&](const Game &a, const Game &b) {
        if (m_sortBySize && a.total() != b.total())
            return a.total() > b.total();
        return coll.compare(a.name, b.name) < 0;
    });
}

QString GamesModel::summary() const
{
    if (m_loading && m_games.isEmpty())
        return QStringLiteral("Reading your Steam libraries…");
    qint64 total = 0, shader = 0;
    for (const Game &g : m_games) {
        total += g.total();
        shader += qMax<qint64>(0, g.shaderBytes);
    }
    return QStringLiteral("%1 games · %2 total · %3 is shader cache").arg(m_games.size()).arg(System::formatSize(total), System::formatSize(shader));
}

QStringList GamesModel::protectedRoots() const
{
    QStringList out;
    for (const QString &lib : SteamFiles::libraries())
        out << lib + QStringLiteral("/steamapps");
    return out;
}

void GamesModel::refresh()
{
    if (m_loading)
        return;
    m_loading = true;
    Q_EMIT loadingChanged();
    Q_EMIT countChanged();
    m_watcher.setFuture(QtConcurrent::run(&GamesModel::scan));
}

QList<GamesModel::Game> GamesModel::scan()
{
    QList<Game> out;
    const QString root = SteamFiles::root();
    QSet<QString> seen;
    QHash<QString, QString> shortcutNames;
    if (!root.isEmpty()) {
        QDir userdata(root + QStringLiteral("/userdata"));
        for (const QString &id : userdata.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            QFile f(userdata.filePath(id + QStringLiteral("/config/shortcuts.vdf")));
            if (!f.open(QIODevice::ReadOnly))
                continue;
            for (const QVariantMap &s : SteamFiles::parseShortcuts(f.readAll()))
                shortcutNames.insert(s.value(QStringLiteral("appid")).toString(), s.value(QStringLiteral("appname")).toString());
        }
    }
    for (const QString &lib : SteamFiles::libraries()) {
        QDir apps(lib + QStringLiteral("/steamapps"));
        for (const QString &acf : apps.entryList({QStringLiteral("appmanifest_*.acf")}, QDir::Files)) {
            QFile f(apps.filePath(acf));
            if (!f.open(QIODevice::ReadOnly))
                continue;
            const QVariantMap st = SteamFiles::parseText(f.readAll()).value(QStringLiteral("AppState")).toMap();
            Game g;
            g.appId = st.value(QStringLiteral("appid")).toString();
            g.name = st.value(QStringLiteral("name")).toString();
            if (g.appId.isEmpty() || g.name.isEmpty() || isTool(g.name) || seen.contains(g.appId))
                continue;
            seen.insert(g.appId);
            g.library = lib;
            g.installDir = apps.filePath(QStringLiteral("common/") + st.value(QStringLiteral("installdir")).toString());
            g.gameBytes = st.value(QStringLiteral("SizeOnDisk")).toLongLong();
            g.compat = apps.filePath(QStringLiteral("compatdata/") + g.appId);
            g.shader = apps.filePath(QStringLiteral("shadercache/") + g.appId);
            g.art = findArt(root, g.appId);
            out << g;
        }
        // Non-Steam games only show up as prefixes; shortcuts.vdf has their names.
        QDir compat(lib + QStringLiteral("/steamapps/compatdata"));
        for (const QString &id : compat.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            if (seen.contains(id) || id == QLatin1String("0"))
                continue;
            if (!shortcutNames.contains(id))
                continue;
            seen.insert(id);
            Game g;
            g.appId = id;
            g.name = shortcutNames.value(id);
            g.library = lib;
            g.compat = compat.filePath(id);
            g.shader = lib + QStringLiteral("/steamapps/shadercache/") + id;
            out << g;
        }
    }
    for (Game &g : out) {
        g.prefixBytes = dirSize(g.compat);
        g.shaderBytes = dirSize(g.shader);
        g.savesBytes = dirSize(g.compat + QStringLiteral("/pfx/drive_c/users/steamuser"));
        QFile v(g.compat + QStringLiteral("/version"));
        if (v.open(QIODevice::ReadOnly))
            g.proton = QString::fromUtf8(v.readLine()).trimmed();
    }
    return out;
}

QVariantMap GamesModel::details(int row) const
{
    if (row < 0 || row >= m_games.size())
        return {};
    const Game &g = m_games.at(row);
    auto part = [](const QString &label, const QString &path, qint64 bytes) {
        return QVariantMap{{QStringLiteral("label"), label},
                           {QStringLiteral("url"), QUrl::fromLocalFile(path)},
                           {QStringLiteral("exists"), QFileInfo(path).isDir()},
                           {QStringLiteral("sizeText"), bytes >= 0 ? System::formatSize(bytes) : QStringLiteral("None")}};
    };
    QString where = QStringLiteral("compatdata/") + g.appId;
    if (!g.proton.isEmpty())
        where += QStringLiteral(" · Proton ") + g.proton;
    QVariantList parts{part(QStringLiteral("Saves"), g.compat + QStringLiteral("/pfx/drive_c/users/steamuser"), g.savesBytes),
                       part(QStringLiteral("Windows C: drive"), g.compat + QStringLiteral("/pfx/drive_c"), g.prefixBytes),
                       part(QStringLiteral("Shader cache"), g.shader, g.shaderBytes),
                       part(QStringLiteral("Game files"), g.installDir, g.gameBytes)};
    return {{QStringLiteral("name"), g.name},
            {QStringLiteral("appId"), g.appId},
            {QStringLiteral("initials"), initials(g.name)},
            {QStringLiteral("where"), where},
            {QStringLiteral("parts"), parts}};
}

QString GamesModel::backupTo(const Game &g, QString *error) const
{
    const QString saves = g.compat + QStringLiteral("/pfx/drive_c/users/steamuser");
    if (!QFileInfo(saves).isDir()) {
        *error = QStringLiteral("%1 has no saves in its prefix yet.").arg(g.name);
        return {};
    }
    QString safeName = g.name;
    safeName.replace(QRegularExpression(QStringLiteral("[/\\\\:*?\"<>|]")), QStringLiteral("_"));
    const QString dir = QDir::homePath() + QStringLiteral("/Huginn Backups/") + safeName;
    QDir().mkpath(dir);
    const QString path = dir + QStringLiteral("/saves ") + QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HHmm")) + QStringLiteral(".zip");
    KZip zip(path);
    if (!zip.open(QIODevice::WriteOnly) || !zip.addLocalDirectory(saves, QStringLiteral("steamuser"))) {
        *error = QStringLiteral("Couldn't write the backup to %1").arg(dir);
        return {};
    }
    zip.close();
    return path;
}

void GamesModel::backupSaves(int row)
{
    if (row < 0 || row >= m_games.size())
        return;
    QString error;
    const QString path = backupTo(m_games.at(row), &error);
    Q_EMIT finished(!path.isEmpty(), path.isEmpty() ? error : QStringLiteral("Saved to Huginn Backups / %1").arg(QFileInfo(QFileInfo(path).path()).fileName()));
}

void GamesModel::clearShaderCache(int row)
{
    if (row < 0 || row >= m_games.size())
        return;
    const Game g = m_games.at(row);
    auto *job = KIO::trash({QUrl::fromLocalFile(g.shader)}, KIO::HideProgressInfo);
    job->setUiDelegate(nullptr);
    connect(job, &KJob::result, this, [this, g](KJob *j) {
        Q_EMIT finished(!j->error(), j->error() ? j->errorString() : QStringLiteral("Cleared the shader cache for %1").arg(g.name));
        refresh();
    });
}

void GamesModel::resetPrefix(int row)
{
    if (row < 0 || row >= m_games.size())
        return;
    const Game g = m_games.at(row);
    QString error;
    if (QFileInfo(g.compat + QStringLiteral("/pfx/drive_c/users/steamuser")).isDir() && backupTo(g, &error).isEmpty()) {
        Q_EMIT finished(false, QStringLiteral("Stopped: the saves backup failed, so the prefix was left alone. ") + error);
        return;
    }
    auto *job = KIO::trash({QUrl::fromLocalFile(g.compat)}, KIO::HideProgressInfo);
    job->setUiDelegate(nullptr);
    connect(job, &KJob::result, this, [this, g](KJob *j) {
        Q_EMIT finished(!j->error(), j->error() ? j->errorString()
                                                : QStringLiteral("Reset %1. Saves are in Huginn Backups, the old prefix is in the trash.").arg(g.name));
        refresh();
    });
}
