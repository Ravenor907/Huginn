#include "places.h"
#include "steam.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QStorageInfo>

namespace
{
QVariantMap place(const QString &name, const QString &kind, const QUrl &url = {})
{
    return {{QStringLiteral("name"), name}, {QStringLiteral("kind"), kind}, {QStringLiteral("url"), url.toString()}};
}
}

Places::Places(QObject *parent)
    : QObject(parent)
{
    rebuild();
    connect(&m_watch, &QTimer::timeout, this, [this] {
        if (cardMounts() != m_lastMounts)
            rebuild();
    });
    m_watch.start(4000);
}

QStringList Places::cardMounts()
{
    QStringList out;
    for (const QStorageInfo &s : QStorageInfo::mountedVolumes()) {
        const QString root = s.rootPath();
        if (!s.isValid() || !s.isReady() || !root.startsWith(QLatin1String("/run/media/")))
            continue;
        // A Ventoy stick's data partition belongs on the USB screen, not in the sidebar.
        if (QFileInfo(root).fileName().compare(QLatin1String("VTOYEFI"), Qt::CaseInsensitive) == 0)
            continue;
        out << root;
    }
    out.sort();
    return out;
}

void Places::rebuild()
{
    m_lastMounts = cardMounts();
    QVariantList l;
    const QString home = QDir::homePath();
    l << place(QStringLiteral("Home"), QStringLiteral("folder"), QUrl::fromLocalFile(home));
    for (const QString &m : std::as_const(m_lastMounts)) {
        const QString label = QFileInfo(m).fileName();
        const bool card = QString::fromUtf8(QStorageInfo(m).device()).contains(QLatin1String("mmcblk"));
        l << place(card ? QStringLiteral("SD Card") : QStringLiteral("USB · ") + label, QStringLiteral("folder"), QUrl::fromLocalFile(m));
    }
    const QString steam = SteamFiles::root();
    if (!steam.isEmpty())
        l << place(QStringLiteral("Steam Library"), QStringLiteral("folder"), QUrl::fromLocalFile(steam + QStringLiteral("/steamapps/common")));
    l << place(QStringLiteral("Games"), QStringLiteral("games"));
    l << place(QStringLiteral("Storage"), QStringLiteral("storage"));
    l << place(QStringLiteral("Screenshots"), QStringLiteral("folder"), QUrl::fromLocalFile(SteamFiles::screenshotsDir()));
    QString emulation = home + QStringLiteral("/Emulation");
    for (const QString &m : std::as_const(m_lastMounts))
        if (QFileInfo(m + QStringLiteral("/Emulation")).isDir())
            emulation = m + QStringLiteral("/Emulation");
    if (QFileInfo(emulation).isDir())
        l << place(QStringLiteral("Emulation"), QStringLiteral("folder"), QUrl::fromLocalFile(emulation));
    l << place(QStringLiteral("Downloads"), QStringLiteral("folder"),
               QUrl::fromLocalFile(QStandardPaths::writableLocation(QStandardPaths::DownloadLocation)));
    l << place(QStringLiteral("Trash"), QStringLiteral("folder"), QUrl(QStringLiteral("trash:/")));
    l << place(QStringLiteral("Network"), QStringLiteral("network"));
    l << place(QStringLiteral("Phone Transfer"), QStringLiteral("transfer"));
    l << place(QStringLiteral("USB Tools"), QStringLiteral("usb"));
    m_list = l;
    Q_EMIT listChanged();
}

QUrl Places::startUrl(int pane) const
{
    if (pane == 0)
        return QUrl::fromLocalFile(QDir::homePath());
    for (const QString &m : m_lastMounts)
        if (!QFileInfo(m + QStringLiteral("/ventoy")).exists())
            return QUrl::fromLocalFile(m);
    return QUrl::fromLocalFile(QStandardPaths::writableLocation(QStandardPaths::DownloadLocation));
}

QVariantList Places::romFolders() const
{
    // EmuDeck and RetroDECK keep one folder per system under Emulation/roms.
    QStringList roots{QDir::homePath() + QStringLiteral("/Emulation/roms")};
    for (const QString &m : m_lastMounts)
        roots << m + QStringLiteral("/Emulation/roms");
    roots << QDir::homePath() + QStringLiteral("/retrodeck/roms");
    QVariantList withFiles, empty;
    for (const QString &root : std::as_const(roots)) {
        QDir dir(root);
        if (!dir.exists())
            continue;
        for (const QString &sys : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot, QDir::Name | QDir::IgnoreCase)) {
            const QString path = dir.filePath(sys);
            const int n = int(QDir(path).entryList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot).size());
            const QVariantMap e{{QStringLiteral("name"), sys}, {QStringLiteral("detail"), n > 0 ? QStringLiteral("%1 items").arg(n) : QStringLiteral("empty")},
                                {QStringLiteral("url"), QUrl::fromLocalFile(path).toString()}};
            (n > 0 ? withFiles : empty) << e;
        }
    }
    return withFiles + empty;
}

QVariantList Places::gameFolders() const
{
    QVariantList out;
    for (const QString &lib : SteamFiles::libraries()) {
        QDir apps(lib + QStringLiteral("/steamapps"));
        for (const QString &acf : apps.entryList({QStringLiteral("appmanifest_*.acf")}, QDir::Files)) {
            QFile f(apps.filePath(acf));
            if (!f.open(QIODevice::ReadOnly))
                continue;
            const QVariantMap st = SteamFiles::parseText(f.readAll()).value(QStringLiteral("AppState")).toMap();
            const QString name = st.value(QStringLiteral("name")).toString();
            const QString dir = apps.filePath(QStringLiteral("common/") + st.value(QStringLiteral("installdir")).toString());
            if (name.isEmpty() || !QFileInfo(dir).isDir() || name.startsWith(QLatin1String("Proton")) || name.startsWith(QLatin1String("Steam Linux Runtime")))
                continue;
            out << QVariantMap{{QStringLiteral("name"), name}, {QStringLiteral("detail"), QStringLiteral("game folder")},
                               {QStringLiteral("url"), QUrl::fromLocalFile(dir).toString()}};
        }
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("name")).toString().compare(b.toMap().value(QStringLiteral("name")).toString(), Qt::CaseInsensitive) < 0;
    });
    return out;
}
