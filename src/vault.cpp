#include "vault.h"
#include "platform/powerwatch.h"
#include "platform/singleinstance.h"
#include "gamepad.h"

#include <QCoreApplication>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QIODevice>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QProcess>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QThread>
#include <QVariant>
#include <QLocale>
#include <algorithm>
#include <cstdio>
#include <memory>

#include <openssl/evp.h>
#include <openssl/rand.h>

namespace
{
const QByteArray kMagic = QByteArrayLiteral("MUNV1");
const int kRounds = 310000;

QString dataDir()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/chat");
    QDir().mkpath(dir);
    return dir;
}

QByteArray random(int n)
{
    QByteArray b(n, 0);
    RAND_bytes(reinterpret_cast<unsigned char *>(b.data()), n);
    return b;
}

QSettings &settings()
{
    static QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    return s;
}
}

Vault::Vault(QObject *parent)
    : QObject(parent)
{
    // Away from Chat: look every few seconds, so the lock is up before anyone comes back.
    m_awayTimer.setInterval(5000);
    connect(&m_awayTimer, &QTimer::timeout, this, [this] { if (!m_awaySince || checkAway()) m_awayTimer.stop(); });
    // Panic lock: both stick clicks held this long.
    m_combo.setSingleShot(true);
    m_combo.setInterval(1000);
    connect(&m_combo, &QTimer::timeout, this, [this] {
        if (!panicLock() || !exists() || locked())
            return;
        lockScreen();
        Q_EMIT panicked();
    });
    // The controller: main.cpp makes it before the vault, as a child of the app.
    if (auto *pad = qApp ? qApp->findChild<Gamepad *>(QString(), Qt::FindDirectChildrenOnly) : nullptr) {
        connect(pad, &Gamepad::pressed, this, [this](const QString &b) { button(b, true); });
        connect(pad, &Gamepad::released, this, [this](const QString &b) { button(b, false); });
        connect(pad, &Gamepad::connectedChanged, this, [this] { m_l3 = m_r3 = false; m_combo.stop(); });
    }
    // "Lock when I leave Huginn": Steam's menu, another app, or the handheld going to sleep.
    // Otherwise the privacy cover goes up at once and the away clock is checked right away.
    if (qGuiApp)
        connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
            if (state == Qt::ApplicationActive) {
                returning();
                return;
            }
            if (lockOnLeave() && exists() && !locked() && !m_screenLocked) {
                lockScreen();
                Q_EMIT panicked();
            }
            leaving();
        });
    // Windows can sleep and wake with the window still in front, so Qt's state never changes.
    Platform::watchPower(this, [this] { sleeping(); }, [this] { woke(); });
}

void Vault::leaving()
{
    checkAway();
    if (!m_covered && exists() && !locked()) {
        m_covered = true;
        Q_EMIT changed();
    }
}

void Vault::returning()
{
    checkAway();   // past the relock time: the lock is up before the cover comes off
    if (m_covered) {
        m_covered = false;
        Q_EMIT changed();
    }
}

void Vault::sleeping()
{
    if (m_asleep)
        return;
    m_asleep = true;
    // Covered before Windows takes the picture it shows on wake. The away clock runs while asleep
    // even with Chat on screen; what it was before comes back on wake.
    m_awayBeforeSleep = m_awaySince;
    if (!locked() && !m_awaySince) {
        m_awaySince = QDateTime::currentMSecsSinceEpoch();
        m_awayTimer.start();
    }
    leaving();
}

void Vault::woke()
{
    if (!m_asleep)
        return;
    m_asleep = false;
    checkAway();
    if (!m_screenLocked) {
        m_awaySince = m_awayBeforeSleep;
        if (m_awaySince)
            m_awayTimer.start();
        else
            m_awayTimer.stop();
    }
    m_awayBeforeSleep = 0;
    // Still in front: uncover. In the background: the cover stays until Huginn is back in front.
    if (!qGuiApp || QGuiApplication::applicationState() == Qt::ApplicationActive)
        returning();
}

QString Vault::keyFile() const { return dataDir() + QStringLiteral("/vault.key"); }
QString Vault::path(const QString &name) const { return dataDir() + QLatin1Char('/') + name; }

bool Vault::exists() const { return QFile::exists(keyFile()); }

bool Vault::usesPattern() const
{
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    return QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("kind")).toString() == QLatin1String("pattern");
}

int Vault::waitSeconds() const
{
    const qint64 until = settings().value(QStringLiteral("vault/until"), 0).toLongLong();
    const qint64 left = until - QDateTime::currentMSecsSinceEpoch();
    return left > 0 ? int((left + 999) / 1000) : 0;
}

bool Vault::unlock(const QString &secret)
{
    if (m_restarting || waitSeconds() > 0)
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    const QByteArray kek = derive(secret, QByteArray::fromBase64(o.value(QStringLiteral("salt")).toString().toLatin1()),
                                  o.value(QStringLiteral("rounds")).toInt(kRounds));
    bool ok = false;
    const QByteArray key = open(kek, QByteArray::fromBase64(o.value(QStringLiteral("wrapped")).toString().toLatin1()), &ok);
    if (!ok || key.size() != 32) {
        // Every 5 wrong tries the wait doubles: 30 s, 1 min, 2 min ... up to an hour (kept across restarts).
        const int fails = settings().value(QStringLiteral("vault/fails"), 0).toInt() + 1;
        settings().setValue(QStringLiteral("vault/fails"), fails);
        if (fails % 5 == 0)
            settings().setValue(QStringLiteral("vault/until"), QDateTime::currentMSecsSinceEpoch() + qMin<qint64>(3600000, qint64(30000) << qMin(fails / 5 - 1, 7)));
        Q_EMIT changed();
        return false;
    }
    settings().setValue(QStringLiteral("vault/fails"), 0);
    const bool first = m_key.isEmpty();
    m_key = key;
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

bool Vault::setSecret(const QString &secret, bool pattern)
{
    if (secret.size() < 4)
        return false;
    if (exists() && m_key.isEmpty())
        return false;   // changing needs the old lock opened first
    const bool first = m_key.isEmpty();
    if (first)
        m_key = random(32);
    const QByteArray salt = random(16);
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("password")}, {QStringLiteral("kind"), pattern ? QStringLiteral("pattern") : QStringLiteral("text")},
                        {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}, {QStringLiteral("rounds"), kRounds},
                        {QStringLiteral("wrapped"), QString::fromLatin1(seal(derive(secret, salt, kRounds), m_key).toBase64())}};
    QSaveFile f(keyFile());
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
    if (!f.commit())
        return false;
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

void Vault::eraseAll()
{
    QDir(dataDir()).removeRecursively();
    m_key.fill(0);
    m_key.clear();
    settings().remove(QStringLiteral("vault"));
    // The inbox address and chat identity go too, so an erased computer doesn't keep the old mailbox.
    settings().remove(QStringLiteral("nostr"));
    settings().remove(QStringLiteral("chat"));
    Q_EMIT changed();
}

bool Vault::write(const QString &name, const QByteArray &data) const
{
    if (m_key.isEmpty() || m_restarting)
        return false;
    QDir().mkpath(QFileInfo(path(name)).absolutePath());
    QSaveFile f(path(name));
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(kMagic + seal(m_key, data));
    return f.commit();
}

QByteArray Vault::read(const QString &name) const
{
    if (m_key.isEmpty())
        return {};
    QFile f(path(name));
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const QByteArray b = f.readAll();
    f.close();
    if (!b.startsWith(kMagic)) {
        write(name, b);   // from before encryption: save it encrypted now
        return b;
    }
    bool ok = false;
    const QByteArray plain = open(m_key, b.mid(kMagic.size()), &ok);
    return ok ? plain : QByteArray();
}

void Vault::remove(const QString &name) const { QFile::remove(path(name)); }

// ------------------------------------------------------------------ panic lock

bool Vault::panicLock() const { return settings().value(QStringLiteral("chatlock/panic"), true).toBool(); }
void Vault::setPanicLock(bool on)
{
    settings().setValue(QStringLiteral("chatlock/panic"), on);
    Q_EMIT changed();
}
bool Vault::lockOnLeave() const { return settings().value(QStringLiteral("chatlock/leave"), false).toBool(); }
void Vault::setLockOnLeave(bool on)
{
    settings().setValue(QStringLiteral("chatlock/leave"), on);
    Q_EMIT changed();
}

void Vault::button(const QString &name, bool down)
{
    if (name == QLatin1String("ls"))
        m_l3 = down;
    else if (name == QLatin1String("rs"))
        m_r3 = down;
    else
        return;
    if (m_l3 && m_r3 && panicLock()) {
        if (!m_combo.isActive())
            m_combo.start();
    } else {
        m_combo.stop();
    }
}

// ------------------------------------------------------------------ encrypted backup

namespace
{
const QByteArray kBackupMagic = QByteArrayLiteral("MUNB1");
const int kMaxHeader = 4096;
const int kMaxName = 4096;
// Huginn's own chat settings (name, public key, inbox) ride along under this name; Muninn skips it.
const QString kSettingsEntry = QStringLiteral("huginn-settings.dat");
const QStringList kSettingsGroups{QStringLiteral("chat"), QStringLiteral("nostr")};

void put32(QByteArray &out, quint32 v)
{
    const char b[4] = {char((v >> 24) & 0xff), char((v >> 16) & 0xff), char((v >> 8) & 0xff), char(v & 0xff)};
    out.append(b, 4);
}

bool get32(const QByteArray &in, qsizetype at, quint32 *v)
{
    if (at < 0 || at + 4 > in.size())
        return false;
    const auto *p = reinterpret_cast<const unsigned char *>(in.constData() + at);
    *v = (quint32(p[0]) << 24) | (quint32(p[1]) << 16) | (quint32(p[2]) << 8) | quint32(p[3]);
    return true;
}

QString sizeText(qint64 n)
{
    if (n < 1024 * 1024)
        return QString::number(qMax<qint64>(1, n / 1024)) + QStringLiteral(" KB");
    return QString::number(double(n) / (1024.0 * 1024.0), 'f', 1) + QStringLiteral(" MB");
}

QByteArray settingsBlob()
{
    QVariantMap all;
    QSettings &s = settings();
    s.sync();
    for (const QString &g : kSettingsGroups) {
        s.beginGroup(g);
        const QStringList keys = s.allKeys();
        for (const QString &k : keys)
            all.insert(g + QLatin1Char('/') + k, s.value(k));
        s.endGroup();
    }
    QByteArray out;
    QDataStream ds(&out, QIODevice::WriteOnly);
    ds.setVersion(QDataStream::Qt_6_0);
    ds << all;
    return out;
}

void applySettingsBlob(const QByteArray &blob)
{
    QVariantMap all;
    QDataStream ds(blob);
    ds.setVersion(QDataStream::Qt_6_0);
    ds >> all;
    if (ds.status() != QDataStream::Ok)
        return;
    QSettings &s = settings();
    for (const QString &g : kSettingsGroups)
        s.remove(g);
    for (auto it = all.cbegin(); it != all.cend(); ++it) {
        const QString group = it.key().section(QLatin1Char('/'), 0, 0);
        if (kSettingsGroups.contains(group))
            s.setValue(it.key(), it.value());
    }
    s.sync();
}

// Starts Huginn again (to load restored chats) and closes this one.
void restartHuginn()
{
    SingleInstance::releaseForRestart();   // the new copy must not find this one still holding the lock
    if (QFile::exists(QStringLiteral("/.flatpak-info")))
        QProcess::startDetached(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("run"), QStringLiteral("io.github.ravenor907.Huginn")});
    else
        QProcess::startDetached(QCoreApplication::applicationFilePath(), QStringList());
    QTimer::singleShot(300, qApp, &QCoreApplication::quit);
}
}

Vault::Files Vault::everything() const
{
    if (m_key.isEmpty())
        return {};
    if (beforeBackup)
        beforeBackup();
    Files files = collect(m_key, dataDir());
    files.emplace_back(kSettingsEntry, settingsBlob());
    return files;
}

bool Vault::takeEverything(const Files &files, QString *error)
{
    if (m_key.isEmpty()) {
        *error = QStringLiteral("Open Chat and unlock it first.");
        return false;
    }
    if (!replaceAll(m_key, dataDir(), files, error))
        return false;
    for (const auto &e : files)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    QTimer::singleShot(3000, this, [] { restartHuginn(); });
    return true;
}

QByteArray Vault::settingsWith(const QVariantMap &set, const QStringList &drop)
{
    QVariantMap all;
    const QByteArray blob = settingsBlob();
    {
        QDataStream ds(blob);
        ds.setVersion(QDataStream::Qt_6_0);
        ds >> all;
    }
    for (const QString &k : drop)
        all.remove(k);
    for (auto it = set.cbegin(); it != set.cend(); ++it)
        all.insert(it.key(), it.value());
    QByteArray out;
    QDataStream ds(&out, QIODevice::WriteOnly);
    ds.setVersion(QDataStream::Qt_6_0);
    ds << all;
    return out;
}

QString Vault::settingsEntry() { return kSettingsEntry; }

QString Vault::backupFolder() const
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
    if (dir.isEmpty())
        dir = QDir::homePath() + QStringLiteral("/Documents");
    return dir;
}

bool Vault::safeName(const QString &name)
{
    if (name.isEmpty() || name.startsWith(QLatin1Char('/')) || name.contains(QLatin1Char('\\')) || name.contains(QChar(0)))
        return false;
    const QStringList parts = name.split(QLatin1Char('/'));
    for (const QString &p : parts)
        if (p.isEmpty() || p == QLatin1String(".") || p == QLatin1String(".."))
            return false;
    return true;
}

QByteArray Vault::packBackup(const QString &password, const Files &files, const QString &app, int rounds)
{
    const QByteArray salt = random(16);
    const QJsonObject h{{QStringLiteral("v"), 1}, {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}, {QStringLiteral("rounds"), rounds},
                        {QStringLiteral("created"), QDateTime::currentDateTimeUtc().toString(Qt::ISODate)}, {QStringLiteral("app"), app}};
    const QByteArray header = QJsonDocument(h).toJson(QJsonDocument::Compact);
    QByteArray body;
    for (const auto &f : files) {
        const QByteArray name = f.first.toUtf8();
        put32(body, quint32(name.size()));
        body.append(name);
        put32(body, quint32(f.second.size()));
        body.append(f.second);
    }
    QByteArray key = derive(password, salt, rounds);
    QByteArray out = kBackupMagic;
    put32(out, quint32(header.size()));
    out.append(header);
    out.append(seal(key, body));
    key.fill(0);
    body.fill(0);
    return out;
}

int Vault::unpackBackup(const QByteArray &bytes, const QString &password, const QString &app, Files *files, QString *created, QString *error)
{
    const auto damaged = [error] {
        *error = QStringLiteral("This backup is damaged.");
        return 1;
    };
    files->clear();
    if (!bytes.startsWith(kBackupMagic)) {
        *error = QStringLiteral("This isn't a Huginn or Muninn backup.");
        return 1;
    }
    quint32 len = 0;
    if (!get32(bytes, kBackupMagic.size(), &len) || len == 0 || len > quint32(kMaxHeader) || kBackupMagic.size() + 4 + qsizetype(len) > bytes.size())
        return damaged();
    const QJsonObject h = QJsonDocument::fromJson(bytes.mid(kBackupMagic.size() + 4, len)).object();
    if (h.value(QStringLiteral("v")).toInt() != 1) {
        *error = h.isEmpty() ? QStringLiteral("This backup is damaged.") : QStringLiteral("This backup is from a newer version. Update Huginn first.");
        return 1;
    }
    const QByteArray salt = QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1());
    const int rounds = h.value(QStringLiteral("rounds")).toInt();
    // Rounds come from the unprotected header: refuse a weakened one rather than trust it.
    if (salt.size() < 16 || rounds < 100000 || rounds > 10000000)
        return damaged();
    if (created)
        *created = h.value(QStringLiteral("created")).toString();
    const QString from = h.value(QStringLiteral("app")).toString();
    if (from != app) {
        *error = from == QLatin1String("muninn") ? QStringLiteral("This is a Muninn backup. Restore it in Muninn.") : QStringLiteral("This isn't a Huginn backup.");
        return 3;
    }
    const qsizetype at = kBackupMagic.size() + 4 + qsizetype(len);
    if (bytes.size() - at < 12 + 16)
        return damaged();
    QByteArray key = derive(password, salt, rounds);
    bool ok = false;
    QByteArray plain = open(key, bytes.mid(at), &ok);
    key.fill(0);
    if (!ok) {
        *error = QStringLiteral("That password doesn't open this backup.");   // or the file was changed: GCM can't tell which
        return 2;
    }
    qsizetype p = 0;
    while (p < plain.size()) {
        quint32 n = 0, dl = 0;
        if (!get32(plain, p, &n) || n == 0 || n > quint32(kMaxName) || qsizetype(n) > plain.size() - p - 4)
            break;
        const QString name = QString::fromUtf8(plain.mid(p + 4, n));
        p += 4 + n;
        if (!get32(plain, p, &dl) || qsizetype(dl) > plain.size() - p - 4)
            break;
        files->emplace_back(name, plain.mid(p + 4, dl));
        p += 4 + dl;
    }
    plain.fill(0);
    if (p != plain.size()) {
        files->clear();
        return damaged();
    }
    return 0;
}

Vault::Files Vault::collect(const QByteArray &key, const QString &dir)
{
    Files out;
    const QDir base(dir);
    QDirIterator it(dir, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString full = it.next();
        const QString name = base.relativeFilePath(full);
        if (name == QLatin1String("vault.key") || !safeName(name))
            continue;
        QFile f(full);
        if (!f.open(QIODevice::ReadOnly))
            continue;
        const QByteArray b = f.readAll();
        if (!b.startsWith(kMagic))
            continue;   // not ours (a half-written temp file): leave it out
        bool ok = false;
        const QByteArray plain = open(key, b.mid(kMagic.size()), &ok);
        if (ok)
            out.emplace_back(name, plain);
    }
    std::sort(out.begin(), out.end(), [](const auto &a, const auto &b) { return a.first < b.first; });
    return out;
}

bool Vault::replaceAll(const QByteArray &key, const QString &dir, const Files &files, QString *error)
{
    // Everything is written and checked in a new folder first, so a failure leaves Chat as it was.
    const QString stage = QDir::cleanPath(dir) + QStringLiteral(".restore");
    QDir(stage).removeRecursively();
    for (const auto &f : files) {
        if (!safeName(f.first) || f.first == QLatin1String("vault.key") || f.first == kSettingsEntry)
            continue;
        const QString target = stage + QLatin1Char('/') + f.first;
        QDir().mkpath(QFileInfo(target).absolutePath());
        QSaveFile out(target);
        if (!out.open(QIODevice::WriteOnly) || out.write(kMagic + seal(key, f.second)) < 0 || !out.commit()) {
            QDir(stage).removeRecursively();
            *error = QStringLiteral("Couldn't write the restored chats. Is the disk full?");
            return false;
        }
    }
    // Swap: clear Chat's folder except the lock, then move the new files in.
    QDir d(dir);
    d.mkpath(QStringLiteral("."));
    const QFileInfoList old = d.entryInfoList(QDir::AllEntries | QDir::Hidden | QDir::System | QDir::NoDotAndDotDot);
    for (const QFileInfo &fi : old) {
        if (fi.fileName() == QLatin1String("vault.key"))
            continue;
        if (fi.isDir() && !fi.isSymLink())
            QDir(fi.absoluteFilePath()).removeRecursively();
        else
            QFile::remove(fi.absoluteFilePath());
    }
    bool all = true;
    const QDir sd(stage);
    QDirIterator it(stage, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString from = it.next();
        const QString to = dir + QLatin1Char('/') + sd.relativeFilePath(from);
        QDir().mkpath(QFileInfo(to).absolutePath());
        QFile::remove(to);
        all &= QFile::rename(from, to);
    }
    QDir(stage).removeRecursively();
    if (!all)
        *error = QStringLiteral("Some restored files couldn't be moved into place.");
    return all;
}

void Vault::backup(const QString &password)
{
    if (m_busy)
        return;
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("Open Chat and unlock it first."));
        return;
    }
    if (password.size() < 8) {
        Q_EMIT done(QStringLiteral("backup"), false, QStringLiteral("The backup password needs at least 8 characters."));
        return;
    }
    if (beforeBackup)
        beforeBackup();
    const QString folder = backupFolder();
    const QString file = folder + QStringLiteral("/huginn-backup-") + QDate::currentDate().toString(Qt::ISODate) + QStringLiteral(".mbk");
    const QByteArray key = m_key;
    const QString dir = dataDir();
    const QByteArray extra = settingsBlob();
    auto result = std::make_shared<QString>();
    auto good = std::make_shared<bool>(false);
    m_busy = true;
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        Files files = collect(key, dir);
        files.emplace_back(kSettingsEntry, extra);
        const QByteArray bytes = packBackup(password, files, QStringLiteral("huginn"));
        QDir().mkpath(folder);
        QSaveFile f(file);
        if (!f.open(QIODevice::WriteOnly) || f.write(bytes) != bytes.size() || !f.commit()) {
            *result = QStringLiteral("Couldn't save the backup in ") + folder + QStringLiteral(". Is the disk full?");
            return;
        }
        *good = true;
        *result = QStringLiteral("Backed up to Documents as ") + QFileInfo(file).fileName() + QStringLiteral(" (") + sizeText(bytes.size()) + QStringLiteral(").");
    });
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, result, good] {
        t->deleteLater();
        if (!self)
            return;
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("backup"), *good, *result);
    });
    t->start();
}

QVariantList Vault::backups() const
{
    QVariantList out;
    const QFileInfoList list = QDir(backupFolder()).entryInfoList({QStringLiteral("*.mbk")}, QDir::Files, QDir::Time);
    for (const QFileInfo &fi : list) {
        // Only the header is readable without the password: which app made it, and when.
        QString app, when;
        QFile f(fi.absoluteFilePath());
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray head = f.read(kBackupMagic.size() + 4 + kMaxHeader);
            quint32 len = 0;
            if (head.startsWith(kBackupMagic) && get32(head, kBackupMagic.size(), &len) && len <= quint32(kMaxHeader)) {
                const QJsonObject h = QJsonDocument::fromJson(head.mid(kBackupMagic.size() + 4, len)).object();
                app = h.value(QStringLiteral("app")).toString();
                const QDateTime c = QDateTime::fromString(h.value(QStringLiteral("created")).toString(), Qt::ISODate);
                if (c.isValid())
                    when = QLocale().toString(c.toLocalTime(), QStringLiteral("d MMM yyyy, h:mm AP"));
            }
        }
        QString detail = app == QLatin1String("huginn") ? QStringLiteral("Huginn backup") : app == QLatin1String("muninn") ? QStringLiteral("Muninn backup (restore it in Muninn)") : QStringLiteral("Not a backup Huginn can read");
        if (!when.isEmpty())
            detail += QStringLiteral(" · ") + when;
        detail += QStringLiteral(" · ") + sizeText(fi.size());
        out.append(QVariantMap{{QStringLiteral("name"), fi.fileName()}, {QStringLiteral("detail"), detail}, {QStringLiteral("url"), fi.absoluteFilePath()},
                             {QStringLiteral("app"), app}});
    }
    return out;
}

void Vault::openBackup(const QString &file, const QString &password)
{
    if (m_busy)
        return;
    forgetOpened();
    if (locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("open"), false, QStringLiteral("Open Chat and unlock it first."));
        return;
    }
    auto files = std::make_shared<Files>();
    auto result = std::make_shared<QString>();
    auto good = std::make_shared<bool>(false);
    m_busy = true;
    Q_EMIT changed();
    QThread *t = QThread::create([=] {
        QFile f(file);
        if (!f.open(QIODevice::ReadOnly)) {
            *result = QStringLiteral("Couldn't open ") + QFileInfo(file).fileName() + QLatin1Char('.');
            return;
        }
        QString created;
        if (unpackBackup(f.readAll(), password, QStringLiteral("huginn"), files.get(), &created, result.get()) != 0)
            return;
        int count = 0;
        for (const auto &e : *files)
            count += e.first == kSettingsEntry ? 0 : 1;
        const QDateTime c = QDateTime::fromString(created, Qt::ISODate);
        *good = true;
        *result = QStringLiteral("Backup from ") + (c.isValid() ? QLocale().toString(c.toLocalTime(), QStringLiteral("d MMM yyyy, h:mm AP")) : QStringLiteral("an unknown date"))
            + QStringLiteral(", ") + QString::number(count) + (count == 1 ? QStringLiteral(" file.") : QStringLiteral(" files."));
    });
    QPointer<Vault> self(this);
    connect(t, &QThread::finished, this, [self, t, files, result, good] {
        t->deleteLater();
        if (!self)
            return;
        if (*good)
            self->m_opened = std::move(*files);
        self->m_busy = false;
        Q_EMIT self->changed();
        Q_EMIT self->done(QStringLiteral("open"), *good, *result);
    });
    t->start();
}

void Vault::forgetOpened()
{
    for (auto &e : m_opened)
        e.second.fill(0);
    m_opened.clear();
}

void Vault::restoreOpened()
{
    if (m_busy || m_opened.empty() || locked() || screenLocked()) {
        Q_EMIT done(QStringLiteral("restore"), false, QStringLiteral("Open the backup again first."));
        return;
    }
    QString error;
    if (!replaceAll(m_key, dataDir(), m_opened, &error)) {
        forgetOpened();
        Q_EMIT done(QStringLiteral("restore"), false, error);
        return;
    }
    for (const auto &e : m_opened)
        if (e.first == kSettingsEntry)
            applySettingsBlob(e.second);
    forgetOpened();
    // Chat still holds the old conversations in memory: drop the key so nothing it saves in the
    // next moment can overwrite what was just restored, then start fresh on the restored files.
    m_key.fill(0);
    m_key.clear();
    m_restarting = true;
    m_busy = true;
    Q_EMIT changed();
    Q_EMIT done(QStringLiteral("restore"), true, QStringLiteral("Restored. Huginn restarts to load your chats."));
    QTimer::singleShot(1500, this, [] { restartHuginn(); });
}

bool Vault::backupSelfTest()
{
    bool all = true;
    const auto check = [&all](const char *what, bool ok) {
        std::printf("backup %s: %s\n", what, ok ? "ok" : "FAILED");
        all &= ok;
    };
    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        check("temp folder", false);
        return false;
    }
    const QByteArray keyA(32, '\x11'), keyB(32, '\x22');
    const QString src = tmp.path() + QStringLiteral("/a/chat"), dst = tmp.path() + QStringLiteral("/b/chat");
    const Files want{{QStringLiteral("chats.json"), QByteArrayLiteral("{\"k\":[{\"text\":\"hi\"}]}")},
                     {QStringLiteral("contacts.json"), QByteArrayLiteral("[]")},
                     {QStringLiteral("photos/abc"), QByteArray(70000, '\x5a')},
                     {QStringLiteral("secrets.json"), QByteArrayLiteral("{\"chatPriv\":\"x\"}")}};
    QString error;
    check("write source folder", replaceAll(keyA, src, want, &error));
    QFile key(src + QStringLiteral("/vault.key"));
    if (key.open(QIODevice::WriteOnly)) {
        key.write("{\"kind\":\"text\"}");
        key.close();
    }
    const Files got = collect(keyA, src);
    check("collect leaves out vault.key", got == want);

    const QByteArray bytes = packBackup(QStringLiteral("correct horse"), got, QStringLiteral("huginn"));
    quint32 len = 0;
    const bool lenOk = bytes.startsWith("MUNB1") && get32(bytes, 5, &len) && len > 0 && len <= 4096;
    const QJsonObject h = QJsonDocument::fromJson(bytes.mid(9, len)).object();
    check("header", lenOk && h.value(QStringLiteral("v")).toInt() == 1 && h.value(QStringLiteral("rounds")).toInt() == 310000
                        && h.value(QStringLiteral("app")).toString() == QLatin1String("huginn")
                        && QByteArray::fromBase64(h.value(QStringLiteral("salt")).toString().toLatin1()).size() == 16
                        && QDateTime::fromString(h.value(QStringLiteral("created")).toString(), Qt::ISODate).isValid());
    check("names and data are not readable", !bytes.contains("chats.json") && !bytes.contains(QByteArray(64, '\x5a')));

    Files back;
    QString created;
    check("wrong password refused", unpackBackup(bytes, QStringLiteral("wrong horse"), QStringLiteral("huginn"), &back, &created, &error) == 2 && back.empty());
    check("Muninn reader refuses a Huginn backup", unpackBackup(bytes, QStringLiteral("correct horse"), QStringLiteral("muninn"), &back, &created, &error) == 3);
    QByteArray changedBytes = bytes;
    changedBytes[changedBytes.size() - 20] = char(changedBytes.at(changedBytes.size() - 20) ^ 1);
    check("changed file refused", unpackBackup(changedBytes, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 2);
    check("not a backup refused", unpackBackup(QByteArrayLiteral("MUNV1 something else"), QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 1);
    check("round trip", unpackBackup(bytes, QStringLiteral("correct horse"), QStringLiteral("huginn"), &back, &created, &error) == 0 && back == want);

    // Restore onto another computer's chat: its own lock stays, everything else is replaced.
    const Files stale{{QStringLiteral("chats.json"), QByteArrayLiteral("old")}, {QStringLiteral("photos/old"), QByteArrayLiteral("old")}};
    replaceAll(keyB, dst, stale, &error);
    QFile otherKey(dst + QStringLiteral("/vault.key"));
    if (otherKey.open(QIODevice::WriteOnly)) {
        otherKey.write("other lock");
        otherKey.close();
    }
    check("restore", replaceAll(keyB, dst, back, &error));
    QFile kept(dst + QStringLiteral("/vault.key"));
    check("restore keeps this computer's lock", kept.open(QIODevice::ReadOnly) && kept.readAll() == "other lock");
    check("restored files open with this computer's key", collect(keyB, dst) == want);
    check("old files removed", !QFile::exists(dst + QStringLiteral("/photos/old")));
    check("unsafe names refused", !safeName(QStringLiteral("../x")) && !safeName(QStringLiteral("/etc/x")) && !safeName(QStringLiteral("a//b"))
                                      && safeName(QStringLiteral("photos/abc")));
    return all;
}

// ------------------------------------------------------------------ the math

QByteArray Vault::seal(const QByteArray &key, const QByteArray &plain)
{
    const QByteArray iv = random(12);
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_EncryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(plain.constData()), int(plain.size()));
    total = len;
    EVP_EncryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len);
    total += len;
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total);
    EVP_CIPHER_CTX_free(c);
    out.resize(total + 16);
    return iv + out;   // the same layout Java's AES/GCM gives: iv, ciphertext, tag
}

QByteArray Vault::open(const QByteArray &key, const QByteArray &data, bool *ok)
{
    *ok = false;
    if (key.size() != 32 || data.size() < 12 + 16)
        return {};
    const QByteArray iv = data.left(12), tag = data.right(16), ct = data.mid(12, data.size() - 28);
    QByteArray out(ct.size(), 0);
    int len = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_DecryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(ct.constData()), int(ct.size()));
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, const_cast<char *>(tag.constData()));
    const bool good = EVP_DecryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + len, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    *ok = good;
    return good ? out : QByteArray();
}

QByteArray Vault::derive(const QString &secret, const QByteArray &salt, int rounds)
{
    const QByteArray pw = secret.toUtf8();
    QByteArray out(32, 0);
    PKCS5_PBKDF2_HMAC(pw.constData(), int(pw.size()), reinterpret_cast<const unsigned char *>(salt.constData()), int(salt.size()), rounds, EVP_sha256(), 32,
                      reinterpret_cast<unsigned char *>(out.data()));
    return out;
}

bool Vault::selfTest()
{
    bool all = true;
    const QByteArray key(32, '\x07');
    bool ok = false;
    const QByteArray sealed = seal(key, "secret chats");
    const bool round = open(key, sealed, &ok) == "secret chats" && ok;
    std::printf("vault seal/open round trip: %s\n", round ? "ok" : "FAILED");
    all &= round;
    open(QByteArray(32, '\x08'), sealed, &ok);
    std::printf("vault wrong key rejected: %s\n", !ok ? "ok" : "FAILED");
    all &= !ok;
    // PBKDF2-HMAC-SHA256("password", "salt", 1 round) from RFC 7914 section 11.
    const bool kdf = derive(QStringLiteral("password"), "salt", 1).toHex() == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b";
    std::printf("vault PBKDF2 test vector: %s\n", kdf ? "ok" : "FAILED");
    all &= kdf;
    return all;
}
