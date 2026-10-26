#include "places.h"
#include "steam.h"

#include <QDir>
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
