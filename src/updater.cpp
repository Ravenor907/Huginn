#include "updater.h"
#include "settings.h"
#include "system.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QProcess>
#include <QTimer>
#include "platform/winupdate.h"
#include <QRegularExpression>
#include <QStandardPaths>
#include <QTimer>
#include <QStringList>
#include <QVariantMap>
#include <openssl/evp.h>
#include <cstdio>

namespace
{
const char *kReleases = "https://api.github.com/repos/Ravenor907/Huginn-Muninn/releases?per_page=100";
const char *kLatestPage = "https://github.com/Ravenor907/Huginn-Muninn/releases/latest";
const char *kDownload = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/";
const char *kAppId = "io.github.ravenor907.Huginn";
// The project's release key (Ed25519, docs/release-key.pem). CI signs every release file with its private half.
const char *kReleaseKey = "705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06";

// Versions have one dot and read as decimals: 0.3 < 0.31 < 0.32 < 0.4 < 1.0.
// An old test build ("0.3 Test 1", from before test builds were dropped) sits just below its version.
qint64 rank(const QString &v)
{
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^(\\d+)\\.(\\d+)( Test \\d+)?")).match(v);
    if (!m.hasMatch())
        return 0;
    const qint64 frac = (m.captured(2) + QStringLiteral("000000")).left(6).toLongLong();
    return (m.captured(1).toLongLong() * 1000000 + frac) * 10 + (m.captured(3).isEmpty() ? 5 : 0);
}
}

Updater::Updater(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_current(QCoreApplication::applicationVersion())
    , m_status(QStringLiteral("Up to date as far as Huginn knows"))
{
}

void Updater::startAutoUpdate()
{
    if (!System::inFlatpak() && !WinUpdate::supported())
        return;   // a development build updates by rebuilding
    if (m_settings && !m_settings->autoUpdate())
        return;   // "Check for updates automatically" is off: nothing asks GitHub until Check now
    fetch(false, true);
}

QString Updater::versionFromTag(const QString &tag)
{
    // Releases are tagged v0.3, v0.31 and so on. Old test tags (v0.3-test1) don't match and are skipped.
    const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("^v(\\d+\\.\\d+)$")).match(tag);
    return m.hasMatch() ? m.captured(1) : QString();
}

bool Updater::newer(const QString &a, const QString &b)
{
    return rank(a) > rank(b);
}

void Updater::check()
{
    fetch(false);
}

QNetworkRequest Updater::request(const QUrl &url) const
{
    QNetworkRequest req(url);
    // A plain agent with no app name or version, so GitHub can't tell which app or version is asking.
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0"));
    return req;
}

void Updater::fetch(bool installAfter, bool autoMode)
{
    if (m_busy)
        return;
    m_busy = true;
    m_status = QStringLiteral("Checking for updates…");
    Q_EMIT changed();
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kReleases)));
    req.setRawHeader("Accept", "application/vnd.github+json");
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        if (r->error() != QNetworkReply::NoError) {
            fetchLatestPage(installAfter, autoMode);
            return;
        }
        QString bestVer, bestTag, bestAsset;
        QByteArray bestDigest;
        for (const QJsonValue &v : QJsonDocument::fromJson(r->readAll()).array()) {
            const QJsonObject rel = v.toObject();
            if (rel.value(QStringLiteral("draft")).toBool())
                continue;
            if (rel.value(QStringLiteral("prerelease")).toBool())
                continue;
            const QString tag = rel.value(QStringLiteral("tag_name")).toString();
            if (!tag.startsWith(QLatin1Char('v')))   // Muninn (the phone app) releases are muninn-v…
                continue;
            const QString ver = versionFromTag(tag);
            if (ver.isEmpty())
                continue;
            QString asset;
            QByteArray digest;
            for (const QJsonValue &a : rel.value(QStringLiteral("assets")).toArray())
                if (a.toObject().value(QStringLiteral("name")).toString().endsWith(WinUpdate::supported() ? WinUpdate::assetName() : QStringLiteral(".flatpak"))) {
                    asset = a.toObject().value(QStringLiteral("browser_download_url")).toString();
                    // GitHub's own SHA-256 of the file ("sha256:..."), when it has one.
                    digest = WinUpdate::parseDigest(a.toObject().value(QStringLiteral("digest")).toString().toLatin1());
                }
            if (asset.isEmpty())
                continue;
            if (bestVer.isEmpty() || newer(ver, bestVer)) {
                bestVer = ver;
                bestTag = tag;
                bestAsset = asset;
                bestDigest = digest;
            }
        }
        m_assetDigest = bestDigest;
        chosen(bestVer, bestTag, bestAsset, installAfter, autoMode);
    });
}

void Updater::fetchLatestPage(bool installAfter, bool autoMode)
{
    m_busy = true;
    QNetworkRequest req = request(QUrl(QString::fromLatin1(kLatestPage)));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    QNetworkReply *r = m_net.get(req);
    connect(r, &QNetworkReply::finished, this, [this, r, installAfter, autoMode] {
        r->deleteLater();
        m_busy = false;
        // .../releases/latest answers with a redirect to .../releases/tag/v0.6
        const QUrl to = r->attribute(QNetworkRequest::RedirectionTargetAttribute).toUrl();
        const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("/releases/tag/([^/?#]+)$")).match(to.path());
        const QString tag = m.hasMatch() ? m.captured(1) : QString();
        const QString ver = versionFromTag(tag);
        if (ver.isEmpty()) {
            m_status = QStringLiteral("Couldn't reach GitHub. Check the connection.");
            Q_EMIT changed();
            return;
        }
        // Every release carries Huginn.flatpak (see .github/workflows/build.yml).
        m_assetDigest.clear();   // the page doesn't say; the .sha256 file beside the zip does
        chosen(ver, tag, QString::fromLatin1(kDownload) + tag + (WinUpdate::supported() ? QStringLiteral("/") + WinUpdate::assetName() : QStringLiteral("/Huginn.flatpak")), installAfter, autoMode);
    });
}

void Updater::chosen(const QString &bestVer, const QString &bestTag, const QString &bestAsset, bool installAfter, bool autoMode)
{
    m_latest = bestVer;
    m_latestTag = bestTag;
    m_assetUrl = bestAsset;
    if (installAfter) {
        if (bestTag.isEmpty()) {
            m_status = QStringLiteral("No release found on GitHub.");
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
    QNetworkRequest req = request(QUrl(url));
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
        const QString path = dir + QStringLiteral("/Huginn-") + tag + (WinUpdate::supported() ? QStringLiteral(".zip") : QStringLiteral(".flatpak"));
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
        // Only a file whose SHA-256 matches the release's is installed: the Windows zip and the Flatpak alike.
        verifyThenInstall(path, tag);
    });
}

void Updater::installFlatpak(const QString &path)
{
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
}

void Updater::failed(const QString &status)
{
    m_busy = false;
    m_auto = false;
    m_status = status;
    Q_EMIT changed();
}

void Updater::verifyThenInstall(const QString &path, const QString &tag)
{
    m_status = QStringLiteral("Checking the download…");
    Q_EMIT changed();
    const QString name = WinUpdate::supported() ? WinUpdate::assetName() : QStringLiteral("Huginn.flatpak");
    auto get = [this, tag](const QString &file) {
        QNetworkRequest req = request(QUrl(QString::fromLatin1(kDownload) + tag + QLatin1Char('/') + file));
        req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
        return m_net.get(req);
    };
    // Huginn-Windows.zip.sig or Huginn.flatpak.sig: the project's signature, published by the release job.
    // Releases from before 0.94 have none, and every update from here on is newer, so it is always required.
    QNetworkReply *s = get(name + QStringLiteral(".sig"));
    connect(s, &QNetworkReply::finished, this, [this, s, path, name, get] {
        s->deleteLater();
        const QByteArray sig = s->error() == QNetworkReply::NoError ? s->read(256) : QByteArray();
        if (!m_assetDigest.isEmpty()) {
            checkThenInstall(path, m_assetDigest, sig);
            return;
        }
        // Huginn-Windows.zip.sha256 or Huginn.flatpak.sha256, published by the release job beside the file.
        QNetworkReply *r = get(name + QStringLiteral(".sha256"));
        connect(r, &QNetworkReply::finished, this, [this, r, path, sig] {
            r->deleteLater();
            checkThenInstall(path, r->error() == QNetworkReply::NoError ? WinUpdate::parseDigest(r->read(4096)) : QByteArray(), sig);
        });
    });
}

void Updater::checkThenInstall(const QString &path, const QByteArray &expected, const QByteArray &sig)
{
    QFile f(path);
    if (expected.size() != 64 || !f.open(QIODevice::ReadOnly)) {
        QFile::remove(path);
        failed(QStringLiteral("The update couldn't be checked, so it wasn't installed. Try again later."));
        return;
    }
    const QByteArray data = f.readAll();
    f.close();
    const QByteArray got = QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex();
    if (got != expected) {
        qWarning("update: SHA-256 %s, release says %s", got.constData(), expected.constData());
        QFile::remove(path);
        failed(QStringLiteral("The download didn't match the release, so it wasn't installed. Try again."));
        return;
    }
    if (!signedByProject(data, sig)) {
        qWarning("update: no valid project signature (%d bytes of .sig)", int(sig.size()));
        QFile::remove(path);
        failed(QStringLiteral("This update isn't signed with the project's key, so it wasn't installed."));
        return;
    }
    if (!WinUpdate::supported()) {
        installFlatpak(path);
        return;
    }
    m_status = QStringLiteral("Installing…");
    Q_EMIT changed();
    WinUpdate::install(path, this, [this](bool ok, const QString &msg) {
        m_busy = false;
        if (!ok)
            m_auto = false;
        m_status = msg;
        Q_EMIT changed();
        if (ok)
            QTimer::singleShot(300, qApp, &QCoreApplication::quit);
    });
}

bool Updater::signedBy(const QByteArray &data, const QByteArray &sig, const QByteArray &publicKey)
{
    if (publicKey.size() != 32 || sig.size() != 64)
        return false;
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, reinterpret_cast<const unsigned char *>(publicKey.constData()), publicKey.size());
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    // Ed25519 takes the whole message at once (no digest of its own to stream into).
    const bool ok = key && ctx && EVP_DigestVerifyInit(ctx, nullptr, nullptr, nullptr, key) == 1
        && EVP_DigestVerify(ctx, reinterpret_cast<const unsigned char *>(sig.constData()), sig.size(),
                            reinterpret_cast<const unsigned char *>(data.constData()), data.size()) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok;
}

bool Updater::signedByProject(const QByteArray &data, const QByteArray &sig)
{
    return signedBy(data, sig, QByteArray::fromHex(kReleaseKey));
}

bool Updater::selfTest(const QString &vectorsPath)
{
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        fprintf(stderr, "release-sig self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonArray cases = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("cases")).toArray();
    auto b = [](const QJsonObject &o, const char *k) { return QByteArray::fromHex(o.value(QLatin1String(k)).toString().toLatin1()); };
    bool ok = !cases.isEmpty();
    for (const QJsonValue &v : cases) {
        const QJsonObject c = v.toObject();
        const bool want = c.value(QStringLiteral("valid")).toBool();
        if (signedBy(b(c, "message"), b(c, "signature"), b(c, "public")) != want) {
            fprintf(stderr, "release-sig self-test: case \"%s\" should be %s\n", qPrintable(c.value(QStringLiteral("name")).toString()), want ? "valid" : "refused");
            ok = false;
        }
    }
    // The built-in project key is a real Ed25519 key, and refuses a signature it didn't make.
    const QByteArray key = QByteArray::fromHex(kReleaseKey);
    EVP_PKEY *pk = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, reinterpret_cast<const unsigned char *>(key.constData()), key.size());
    const bool keyOk = key.size() == 32 && pk;
    EVP_PKEY_free(pk);
    const QJsonObject first = cases.isEmpty() ? QJsonObject() : cases.first().toObject();
    const bool refusesOthers = !signedByProject(b(first, "message"), b(first, "signature"));
    if (!keyOk || !refusesOthers) {
        fprintf(stderr, "release-sig self-test: the built-in project key %s\n", keyOk ? "accepted someone else's signature" : "isn't a valid Ed25519 key");
        ok = false;
    }
    fprintf(stderr, "release-sig self-test: %s (%d cases)\n", ok ? "passed" : "FAILED", int(cases.size()));
    return ok;
}
