#include "system.h"
#include "settings.h"

#include <QCollator>
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QImage>
#include <QImageReader>
#include <QLocale>
#include <QPainter>
#include <QProcess>
#include <QQuickWindow>
#include <QScreen>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QTimer>
#include <algorithm>
#include <cmath>

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
}

QString System::version() const
{
    return QCoreApplication::applicationVersion();
}

bool System::tvLayout() const
{
    const QString mode = m_settings->layout();
    if (mode == QLatin1String("tv"))
        return true;
    if (mode == QLatin1String("handheld"))
        return false;
    // Auto: a screen bigger than 16 inches is a TV or a monitor across the room.
    const QScreen *s = QGuiApplication::primaryScreen();
    if (!s)
        return false;
    const QSizeF mm = s->physicalSize();
    const double diagonalInches = std::hypot(mm.width(), mm.height()) / 25.4;
    return diagonalInches > 16.0 && diagonalInches < 200.0;
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

int System::runHost(const QStringList &args, QByteArray *out, const QByteArray &input, int timeoutMs)
{
    QProcess p;
    QStringList cmd = args;
    if (inFlatpak())
        cmd = QStringList{QStringLiteral("flatpak-spawn"), QStringLiteral("--host")} + args;
    const QString program = cmd.takeFirst();
    p.start(program, cmd);
    if (!p.waitForStarted(5000))
        return -1;
    if (!input.isEmpty())
        p.write(input);
    p.closeWriteChannel();
    if (!p.waitForFinished(timeoutMs)) {
        p.kill();
        return -1;
    }
    if (out)
        *out = p.readAllStandardOutput();
    return p.exitStatus() == QProcess::NormalExit ? p.exitCode() : -1;
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

QString System::kindOf(const QUrl &url) const
{
    const QString name = url.fileName().toLower();
    static const QStringList text = {".txt", ".md", ".log", ".cfg", ".ini", ".conf", ".json", ".xml", ".vdf", ".acf", ".yml", ".yaml", ".sh", ".py", ".csv", ".desktop"};
    for (const QString &t : text)
        if (name.endsWith(t))
            return QStringLiteral("text");
    if (!QImageReader::imageFormat(url.toLocalFile()).isEmpty())
        return QStringLiteral("image");
    return QStringLiteral("file");
}

QVariantMap System::fileInfo(const QUrl &url) const
{
    QVariantMap m;
    const QFileInfo fi(url.toLocalFile());
    if (!fi.exists())
        return m;
    m[QStringLiteral("name")] = fi.fileName();
    QVariantList rows;
    auto row = [&rows](const QString &k, const QString &v) { rows << QVariantMap{{QStringLiteral("k"), k}, {QStringLiteral("v"), v}}; };
    QString summary = formatSize(fi.size());
    QImageReader reader(fi.filePath());
    const QSize dims = reader.size();
    if (dims.isValid()) {
        summary = QStringLiteral("%1 × %2 · %3").arg(dims.width()).arg(dims.height()).arg(summary);
        row(QStringLiteral("Format"), QString::fromLatin1(reader.format()).toUpper() + QStringLiteral(" · %1 × %2").arg(dims.width()).arg(dims.height()));
    }
    row(QStringLiteral("Modified"), QLocale(QLocale::English).toString(fi.lastModified(), QStringLiteral("MMM d, yyyy · h:mm AP")));
    row(QStringLiteral("Size"), formatSize(fi.size()));
    row(QStringLiteral("Folder"), displayPath(QUrl::fromLocalFile(fi.absolutePath())));
    // Steam screenshots live in .../760/remote/<appid>/screenshots.
    const QStringList parts = fi.absolutePath().split(QLatin1Char('/'));
    const int remote = parts.indexOf(QStringLiteral("remote"));
    if (remote > 0 && remote + 1 < parts.size())
        row(QStringLiteral("From"), QStringLiteral("Steam app ") + parts.at(remote + 1));
    m[QStringLiteral("summary")] = summary;
    m[QStringLiteral("rows")] = rows;
    return m;
}

QString System::readText(const QUrl &url, int maxBytes) const
{
    QFile f(url.toLocalFile());
    if (!f.open(QIODevice::ReadOnly))
        return QStringLiteral("Huginn couldn't open this file.");
    QByteArray data = f.read(maxBytes);
    QString text = QString::fromUtf8(data);
    if (f.size() > maxBytes)
        text += QStringLiteral("\n\n… (only the first %1 is shown)").arg(formatSize(maxBytes));
    return text;
}

QVariantList System::imagesBeside(const QUrl &url) const
{
    QVariantList out;
    const QFileInfo fi(url.toLocalFile());
    QDir dir = fi.absoluteDir();
    QStringList names = dir.entryList(QDir::Files | QDir::Readable);
    QCollator coll;
    coll.setNumericMode(true);
    coll.setCaseSensitivity(Qt::CaseInsensitive);
    std::sort(names.begin(), names.end(), [&](const QString &a, const QString &b) { return coll.compare(a, b) < 0; });
    for (const QString &n : std::as_const(names)) {
        const QString path = dir.filePath(n);
        if (!QImageReader::imageFormat(path).isEmpty())
            out << QUrl::fromLocalFile(path).toString();
    }
    if (out.isEmpty())
        out << url.toString();
    return out;
}

QVariantList System::sampleImages()
{
    // The CI tour has no pictures to show, so it paints a few.
    QVariantList out;
    const QString dir = (m_tourDir.isEmpty() ? QDir::tempPath() : m_tourDir) + QStringLiteral("/samples");
    QDir().mkpath(dir);
    const QList<QColor> tones = {QColor("#3A3A36"), QColor("#3A3230"), QColor("#33343A"), QColor("#323831")};
    for (int i = 0; i < tones.size(); ++i) {
        QImage img(1920, 1080, QImage::Format_RGB32);
        img.fill(tones.at(i));
        QPainter p(&img);
        p.setPen(QPen(QColor("#6E6A62"), 6));
        p.drawRect(660, 340, 600, 400);
        p.drawLine(660, 640, 860, 480);
        p.drawLine(860, 480, 1020, 600);
        p.drawLine(1020, 600, 1260, 420);
        p.end();
        const QString path = dir + QStringLiteral("/screenshot_%1.png").arg(1021 - i);
        img.save(path);
        out << QUrl::fromLocalFile(path).toString();
    }
    return out;
}

QUrl System::homeUrl() const { return QUrl::fromLocalFile(QDir::homePath()); }

QUrl System::parentUrl(const QUrl &url) const
{
    return url.adjusted(QUrl::RemoveFilename | QUrl::StripTrailingSlash);
}

QString System::parentPath(const QUrl &url) const
{
    return displayPath(parentUrl(url));
}

QString System::fileName(const QVariant &url) const
{
    return QUrl(url.toString()).fileName();
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
