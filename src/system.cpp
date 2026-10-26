#include "system.h"
#include "settings.h"

#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QSet>
#include <QFileInfo>
#include <QGuiApplication>
#include <QImage>
#include <QLocale>
#include <QPair>
#include <QProcess>
#include <QQuickWindow>
#include <QScreen>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QTimer>
#include <QVariantMap>
#include <algorithm>

int System::s_qmlWarnings = 0;

System::System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_tourDir(tourDir)
    , m_windowed(windowed)
{
    m_gameMode = qEnvironmentVariable("XDG_CURRENT_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || !qEnvironmentVariableIsEmpty("GAMESCOPE_WAYLAND_DISPLAY")
        || qEnvironmentVariable("SteamGamepadUI") == QLatin1String("1");
    connect(settings, &Settings::changed, this, &System::layoutChanged);
    if (QScreen *screen = QGuiApplication::primaryScreen())
        connect(screen, &QScreen::geometryChanged, this, &System::layoutChanged);
    connect(qGuiApp, &QGuiApplication::primaryScreenChanged, this, &System::layoutChanged);
}

QString System::version() const
{
    return QCoreApplication::applicationVersion();
}

void System::setDesktop(bool on)
{
    if (on == m_desktop)
        return;
    m_desktop = on;
    Q_EMIT layoutChanged();
}

double System::uiScale() const
{
    // Handheld: drawn for a 720-line screen, so a 1080p handheld gets 1.5x, and so on.
    // Desktop: smaller, for a mouse and keyboard at arm's length (Qt already handles HiDPI).
    // The owner's Size setting multiplies either.
    double base = 1.5;   // the CI tour renders as a 1080p handheld, like the Ally X
    if (m_tourDir.isEmpty()) {
        base = 1.0;
        if (const QScreen *screen = QGuiApplication::primaryScreen())
            base = qBound(1.0, screen->geometry().height() / 720.0, 3.0);
    }
    if (m_desktop) {
        base = 0.85;
        if (m_tourDir.isEmpty())
            if (const QScreen *screen = QGuiApplication::primaryScreen())
                base = 0.85 * qBound(1.0, screen->geometry().height() / 1080.0, 2.0);
    }
    const double size = qBound(50, m_settings->uiSize().toInt(), 200) / 100.0;
    return base * size;
}

QString System::formatSize(qint64 bytes)
{
    if (bytes < 0)
        return {};
    return QLocale(QLocale::English).formattedDataSize(bytes, 1, QLocale::DataSizeTraditionalFormat);
}

QString System::displayPath(const QUrl &url)
{
    if (!url.isLocalFile()) {
        if (url.scheme() == QLatin1String("trash"))
            return QStringLiteral("Trash");
        QString host = url.host();
        return (host.isEmpty() ? url.scheme() : host) + (url.path().isEmpty() || url.path() == QLatin1String("/") ? QString() : url.path().replace(QLatin1Char('/'), QStringLiteral(" / ")));
    }
    QString p = url.toLocalFile();
    const QString home = QDir::homePath();
    // SD cards and USB drives mount under /run/media/<user>/<label>.
    static const QString media = QStringLiteral("/run/media/");
    if (p.startsWith(media)) {
        const QStringList parts = p.mid(media.size()).split(QLatin1Char('/'), Qt::SkipEmptyParts);
        if (parts.size() >= 2) {
            QStringList rest = parts.mid(2);
            const QString label = parts.at(1);
            const bool card = QString::fromUtf8(QStorageInfo(p).device()).contains(QLatin1String("mmcblk"));
            const QString name = card ? QStringLiteral("SD Card") : label;
            rest.prepend(name);
            return rest.join(QStringLiteral(" / "));
        }
    }
    if (p == home)
        return QStringLiteral("Home");
    if (p.startsWith(home + QLatin1Char('/'))) {
        QString rest = p.mid(home.size() + 1);
        if (rest.startsWith(QLatin1String(".local/share/Steam/")))
            rest = QStringLiteral("Steam / ") + rest.mid(19);
        return QStringLiteral("Home / ") + rest.split(QLatin1Char('/'), Qt::SkipEmptyParts).join(QStringLiteral(" / "));
    }
    return p == QLatin1String("/") ? QStringLiteral("System") : p.split(QLatin1Char('/'), Qt::SkipEmptyParts).join(QStringLiteral(" / "));
}

bool System::inFlatpak()
{
    return QFile::exists(QStringLiteral("/.flatpak-info"));
}

void System::openSteamUrl(const QString &url)
{
    QStringList cmd{QStringLiteral("xdg-open"), url};
    if (inFlatpak())
        cmd = QStringList{QStringLiteral("flatpak-spawn"), QStringLiteral("--host"), QStringLiteral("xdg-open"), url};
    QProcess::startDetached(cmd.takeFirst(), cmd);
}

void System::showSteamKeyboard()
{
    // Steam's own keyboard types into whatever has focus. Desktop users have a real keyboard.
    if (m_gameMode)
        openSteamUrl(QStringLiteral("steam://open/keyboard"));
}

void System::hideSteamKeyboard()
{
    if (m_gameMode)
        openSteamUrl(QStringLiteral("steam://close/keyboard"));
}

QVariantList System::sendableFiles(int perFolder) const
{
    const QList<QPair<QString, QStandardPaths::StandardLocation>> places{
        {QStringLiteral("Downloads"), QStandardPaths::DownloadLocation},
        {QStringLiteral("Documents"), QStandardPaths::DocumentsLocation},
        {QStringLiteral("Pictures"), QStandardPaths::PicturesLocation},
        {QStringLiteral("Videos"), QStandardPaths::MoviesLocation},
        {QStringLiteral("Music"), QStandardPaths::MusicLocation}};
    const QLocale en(QLocale::English);
    const QDate today = QDate::currentDate();
    QSet<QString> seen;
    QVariantList out;
    for (const auto &place : places) {
        const QString root = QStandardPaths::writableLocation(place.second);
        if (root.isEmpty() || root == QDir::homePath() || seen.contains(root) || !QDir(root).exists())
            continue;
        seen.insert(root);
        // The folder and one level below it: enough to find what just landed, and quick on a big Downloads.
        const QDir dir(root);
        QList<QFileInfo> found = dir.entryInfoList(QDir::Files | QDir::Readable);
        for (const QFileInfo &sub : dir.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot | QDir::Readable))
            if (found.size() < 3000)
                found += QDir(sub.absoluteFilePath()).entryInfoList(QDir::Files | QDir::Readable);
        std::sort(found.begin(), found.end(), [](const QFileInfo &a, const QFileInfo &b) { return a.lastModified() > b.lastModified(); });
        QVariantList items;
        for (const QFileInfo &f : found.mid(0, perFolder)) {
            const QDate d = f.lastModified().date();
            const QString when = d == today ? QStringLiteral("Today") : d == today.addDays(-1) ? QStringLiteral("Yesterday") : en.toString(d, QStringLiteral("MMM d"));
            items << QVariantMap{{QStringLiteral("name"), f.fileName()}, {QStringLiteral("detail"), formatSize(f.size()) + QStringLiteral(" · ") + when},
                                 {QStringLiteral("url"), QUrl::fromLocalFile(f.absoluteFilePath()).toString()}, {QStringLiteral("icon"), QStringLiteral("file")}};
        }
        if (!items.isEmpty())
            out << QVariantMap{{QStringLiteral("header"), place.first}, {QStringLiteral("items"), items}};
    }
    return out;
}

void System::grab(QQuickWindow *window, const QString &name)
{
    if (m_tourDir.isEmpty() || !window)
        return;
    QDir().mkpath(m_tourDir);
    const QImage img = window->grabWindow();
    img.save(m_tourDir + QLatin1Char('/') + name + QStringLiteral(".png"));
}

void System::tourFinished()
{
    QTimer::singleShot(0, qApp, [] { QCoreApplication::exit(s_qmlWarnings > 0 ? 3 : 0); });
}

void System::setKeepAwake(bool on, const QString &reason)
{
    if (on == m_awake)
        return;
    m_awake = on;
    QDBusConnection bus = QDBusConnection::sessionBus();
    auto call = [&bus](const QString &service, const QString &path, const QString &iface, const QString &method, const QVariantList &args) {
        QDBusMessage m = QDBusMessage::createMethodCall(service, path, iface, method);
        m.setArguments(args);
        return bus.call(m, QDBus::Block, 2000);
    };
    if (on) {
        // The desktop portal is the sandbox-friendly way; the screensaver interface covers sessions without it.
        const QDBusMessage r = call(QStringLiteral("org.freedesktop.portal.Desktop"), QStringLiteral("/org/freedesktop/portal/desktop"),
                                    QStringLiteral("org.freedesktop.portal.Inhibit"), QStringLiteral("Inhibit"),
                                    {QString(), uint(4 | 8), QVariantMap{{QStringLiteral("reason"), reason}}});
        if (r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty())
            m_inhibitHandle = r.arguments().first().value<QDBusObjectPath>().path();
        const QDBusMessage c = call(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"),
                                    QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("Inhibit"), {QStringLiteral("Huginn"), reason});
        if (c.type() == QDBusMessage::ReplyMessage && !c.arguments().isEmpty())
            m_screensaverCookie = c.arguments().first().toUInt();
    } else {
        if (!m_inhibitHandle.isEmpty())
            call(QStringLiteral("org.freedesktop.portal.Desktop"), m_inhibitHandle, QStringLiteral("org.freedesktop.portal.Request"), QStringLiteral("Close"), {});
        m_inhibitHandle.clear();
        if (m_screensaverCookie)
            call(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"),
                 QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("UnInhibit"), {m_screensaverCookie});
        m_screensaverCookie = 0;
    }
}
