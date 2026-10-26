#include "updater.h"
#include "settings.h"
#include "system.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QProcess>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QTimer>

namespace
{
const char *kReleases = "https://api.github.com/repos/Ravenor907/Huginn/releases?per_page=30";
const char *kAppId = "io.github.ravenor907.Huginn";

// "0.2 Test 3" -> {0, 2, 3}; a stable "0.2" ranks above any "0.2 Test n".
QList<int> parts(const QString &v)
{
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^(\\d+)\\.(\\d+)(?: Test (\\d+))?")).match(v);
    if (!m.hasMatch())
        return {0, 0, 0};
    return {m.captured(1).toInt(), m.captured(2).toInt(), m.captured(3).isEmpty() ? 1000000 : m.captured(3).toInt()};
}
}

Updater::Updater(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_current(QCoreApplication::applicationVersion())
    , m_status(QStringLiteral("Up to date as far as Huginn knows"))
{
    connect(settings, &Settings::changed, this, [this] {
        m_latestTag.clear();
        Q_EMIT changed();
    });
}

void Updater::startAutoUpdate()
{
    if (!System::inFlatpak())
        return;   // a development build updates by rebuilding
    fetch(m_settings->channel() != QLatin1String("testing"), false, true);
}

QString Updater::versionFromTag(const QString &tag)
{
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^v?(\\d+\\.\\d+)(?:-test(\\d+))?$"), QRegularExpression::CaseInsensitiveOption).match(tag);
    if (!m.hasMatch())
        return {};
    return m.captured(2).isEmpty() ? m.captured(1) : m.captured(1) + QStringLiteral(" Test ") + m.captured(2);
}

bool Updater::newer(const QString &a, const QString &b)
{
    return parts(a) > parts(b);
}

void Updater::check()
{
    fetch(m_settings->channel() != QLatin1String("testing"), false);
}

void Updater::rollback()
{
    fetch(true, true);
}

void Updater::fetch(bool stableOnly, bool installAfter, bool autoMode)
{
    if (m_busy)
        return;
    m_busy = true;
    m_status = QStringLiteral("Checking for updates…");
    Q_EMIT changed();
    QNetworkRequest req(QUrl(QString::fromLatin1(kReleases)));
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Huginn/") + m_current);
    req.setRawHeader("Accept", "application/vnd.github+json");
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, stableOnly, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        if (r->error() != QNetworkReply::NoError) {
            m_status = QStringLiteral("Couldn't reach GitHub. Check the connection.");
            Q_EMIT changed();
            return;
        }
        QString bestVer, bestTag, bestAsset;
        for (const QJsonValue &v : QJsonDocument::fromJson(r->readAll()).array()) {
            const QJsonObject rel = v.toObject();
            if (rel.value(QStringLiteral("draft")).toBool())
                continue;
            if (stableOnly && rel.value(QStringLiteral("prerelease")).toBool())
                continue;
            const QString tag = rel.value(QStringLiteral("tag_name")).toString();
            const QString ver = versionFromTag(tag);
            if (ver.isEmpty())
                continue;
            QString asset;
            for (const QJsonValue &a : rel.value(QStringLiteral("assets")).toArray())
                if (a.toObject().value(QStringLiteral("name")).toString().endsWith(QLatin1String(".flatpak")))
                    asset = a.toObject().value(QStringLiteral("browser_download_url")).toString();
            if (asset.isEmpty())
                continue;
            if (bestVer.isEmpty() || newer(ver, bestVer)) {
                bestVer = ver;
                bestTag = tag;
                bestAsset = asset;
            }
        }
        m_latest = bestVer;
        m_latestTag = bestTag;
        m_assetUrl = bestAsset;
        if (installAfter) {
            if (bestTag.isEmpty()) {
                m_status = QStringLiteral("There's no stable release to go back to yet.");
                Q_EMIT changed();
                return;
            }
            download(bestAsset, bestTag);
            return;
        }
        if (autoMode && available()) {
            m_auto = true;
            download(bestAsset, bestTag);
            return;
        }
        m_status = available() ? QStringLiteral("Huginn %1 is ready to install").arg(bestVer) : QStringLiteral("Huginn is up to date");
        Q_EMIT changed();
    });
}

void Updater::install()
{
    if (m_busy || m_assetUrl.isEmpty())
        return;
    download(m_assetUrl, m_latestTag);
}

void Updater::download(const QString &url, const QString &tag)
{
    m_busy = true;
    m_status = QStringLiteral("Downloading Huginn %1…").arg(versionFromTag(tag));
    Q_EMIT changed();
    QNetworkRequest req{QUrl(url)};
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Huginn/") + m_current);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, tag] {
        r->deleteLater();
        if (r->error() != QNetworkReply::NoError) {
            m_busy = false;
            m_auto = false;
            m_status = QStringLiteral("The download failed. Try again.");
            Q_EMIT changed();
            return;
        }
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
        QDir().mkpath(dir);
        const QString path = dir + QStringLiteral("/Huginn-") + tag + QStringLiteral(".flatpak");
        QFile f(path);
        if (!f.open(QIODevice::WriteOnly)) {
            m_busy = false;
            m_auto = false;
            m_status = QStringLiteral("Couldn't save the update.");
            Q_EMIT changed();
            return;
        }
        f.write(r->readAll());
        f.close();
        m_status = QStringLiteral("Installing…");
        Q_EMIT changed();
        auto *p = new QProcess(this);
        QStringList args{QStringLiteral("flatpak"), QStringLiteral("install"), QStringLiteral("--user"), QStringLiteral("--noninteractive"),
                         QStringLiteral("--reinstall"), QStringLiteral("--bundle"), path};
        QString program = QStringLiteral("flatpak-spawn");
        if (System::inFlatpak())
            args.prepend(QStringLiteral("--host"));
        else
            program = args.takeFirst();
        connect(p, &QProcess::finished, this, [this, p](int code, QProcess::ExitStatus st) {
            p->deleteLater();
            m_busy = false;
            if (st != QProcess::NormalExit || code != 0) {
                m_auto = false;
                m_status = QStringLiteral("Installing the update failed.");
                Q_EMIT changed();
                return;
            }
            m_status = QStringLiteral("Updated. Restarting…");
            Q_EMIT changed();
            QProcess::startDetached(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("run"), QString::fromLatin1(kAppId)});
            QTimer::singleShot(300, qApp, &QCoreApplication::quit);
        });
        p->start(program, args);
    });
}
