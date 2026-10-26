#include "usb.h"
#include <QtConcurrent>
#include <QFutureWatcher>
#include <QCryptographicHash>
#include "system.h"

#include <QDBusArgument>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusMetaType>
#include <QDBusPendingCallWatcher>
#include <QDBusReply>
#include <QDBusUnixFileDescriptor>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSettings>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QUrl>

#include <fcntl.h>
#include <signal.h>
#include <unistd.h>

using DBusInterfaces = QMap<QString, QVariantMap>;
using DBusObjects = QMap<QDBusObjectPath, DBusInterfaces>;
Q_DECLARE_METATYPE(DBusInterfaces)
Q_DECLARE_METATYPE(DBusObjects)

namespace
{
const QString kService = QStringLiteral("org.freedesktop.UDisks2");
const QString kBlock = QStringLiteral("org.freedesktop.UDisks2.Block");
const QString kDrive = QStringLiteral("org.freedesktop.UDisks2.Drive");
const QString kPart = QStringLiteral("org.freedesktop.UDisks2.Partition");
const QString kTable = QStringLiteral("org.freedesktop.UDisks2.PartitionTable");
const QString kFs = QStringLiteral("org.freedesktop.UDisks2.Filesystem");
const QString kRulePath = QStringLiteral("/etc/polkit-1/rules.d/49-huginn-usb.rules");

QVariantMap quiet()
{
    return {{QStringLiteral("auth.no_user_interaction"), true}};
}

QString pathOf(const QVariant &v)
{
    return qvariant_cast<QDBusObjectPath>(v).path();
}

QStringList mountPoints(const QVariant &v)
{
    QStringList out;
    QDBusArgument arg = v.value<QDBusArgument>();
    if (arg.currentType() != QDBusArgument::ArrayType)
        return out;
    arg.beginArray();
    while (!arg.atEnd()) {
        QByteArray b;
        arg >> b;
        while (b.endsWith('\0'))
            b.chop(1);
        if (!b.isEmpty())
            out << QString::fromUtf8(b);
    }
    arg.endArray();
    return out;
}

QDBusMessage call(const QString &path, const QString &iface, const QString &method, const QVariantList &args, int timeout = 25000)
{
    QDBusMessage m = QDBusMessage::createMethodCall(kService, path, iface, method);
    m.setArguments(args);
    return QDBusConnection::systemBus().call(m, QDBus::Block, timeout);
}

bool ruleMarked()
{
    return QSettings(QStringLiteral("Huginn"), QStringLiteral("Huginn")).value(QStringLiteral("usbRule"), false).toBool();
}

void markRule(bool on)
{
    QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    s.setValue(QStringLiteral("usbRule"), on);
    s.sync();
}

QString helperPath()
{
    const QString env = qEnvironmentVariable("HUGINN_DRIVETOOL");
    if (!env.isEmpty())
        return env;
    return QStringLiteral("/app/libexec/huginn-drivetool");
}
}

UsbManager::UsbManager(QObject *parent)
    : QObject(parent)
{
    qDBusRegisterMetaType<DBusInterfaces>();
    qDBusRegisterMetaType<DBusObjects>();
    m_debounce.setSingleShot(true);
    m_debounce.setInterval(700);
    connect(&m_debounce, &QTimer::timeout, this, &UsbManager::refresh);
    auto bus = QDBusConnection::systemBus();
    bus.connect(kService, QStringLiteral("/org/freedesktop/UDisks2"), QStringLiteral("org.freedesktop.DBus.ObjectManager"),
                QStringLiteral("InterfacesAdded"), &m_debounce, SLOT(start()));
    bus.connect(kService, QStringLiteral("/org/freedesktop/UDisks2"), QStringLiteral("org.freedesktop.DBus.ObjectManager"),
                QStringLiteral("InterfacesRemoved"), &m_debounce, SLOT(start()));
}

bool UsbManager::rawReady() const
{
    return ruleMarked();
}

QString UsbManager::ruleText(const QString &user)
{
    QString safeUser = user;
    safeUser.remove(QRegularExpression(QStringLiteral("[^A-Za-z0-9._-]")));
    return QStringLiteral(
               "// Added by Huginn (one-time setup). Lets %1 open USB drives byte by byte\n"
               "// (installing Ventoy, writing images) without a password, USB drives only.\n"
               "// Remove it from Huginn's Settings, or delete this file.\n"
               "polkit.addRule(function(action, subject) {\n"
               "    if (action.id == \"org.freedesktop.udisks2.open-device\" &&\n"
               "        subject.user == \"%1\" &&\n"
               "        action.lookup(\"drive.removable.bus\") == \"usb\") {\n"
               "        return polkit.Result.YES;\n"
               "    }\n"
               "});\n")
        .arg(safeUser);
}

// ---------------------------------------------------------------- finding drives

QList<UsbManager::Drive> UsbManager::scan(QString *error) const
{
    QList<Drive> out;
    QDBusMessage m = QDBusMessage::createMethodCall(kService, QStringLiteral("/org/freedesktop/UDisks2"),
                                                    QStringLiteral("org.freedesktop.DBus.ObjectManager"), QStringLiteral("GetManagedObjects"));
    QDBusReply<DBusObjects> reply = QDBusConnection::systemBus().call(m, QDBus::Block, 10000);
    if (!reply.isValid()) {
        if (error)
            *error = QStringLiteral("Couldn't talk to the system's drive service (udisks).");
        return out;
    }
    const DBusObjects objects = reply.value();

    QMap<QString, Drive> byDrive;
    for (auto it = objects.cbegin(); it != objects.cend(); ++it) {
        const DBusInterfaces &ifs = it.value();
        if (!ifs.contains(kDrive))
            continue;
        const QVariantMap d = ifs.value(kDrive);
        if (d.value(QStringLiteral("ConnectionBus")).toString() != QLatin1String("usb"))
            continue;
        if (d.value(QStringLiteral("Size")).toULongLong() == 0)
            continue;
        Drive drive;
        drive.id = it.key().path();
        drive.size = qint64(d.value(QStringLiteral("Size")).toULongLong());
        const QString vendor = d.value(QStringLiteral("Vendor")).toString().trimmed();
        const QString model = d.value(QStringLiteral("Model")).toString().trimmed();
        drive.model = (vendor + QLatin1Char(' ') + model).trimmed();
        drive.title = QStringLiteral("USB drive · ") + System::formatSize(drive.size);
        byDrive.insert(drive.id, drive);
    }
    // The whole-disk block of each USB drive, never a system disk.
    for (auto it = objects.cbegin(); it != objects.cend(); ++it) {
        const DBusInterfaces &ifs = it.value();
        if (!ifs.contains(kBlock) || ifs.contains(kPart))
            continue;
        const QVariantMap b = ifs.value(kBlock);
        const QString drive = pathOf(b.value(QStringLiteral("Drive")));
        if (!byDrive.contains(drive) || b.value(QStringLiteral("HintSystem")).toBool())
            continue;
        byDrive[drive].block = it.key().path();
    }
    for (auto it = objects.cbegin(); it != objects.cend(); ++it) {
        const DBusInterfaces &ifs = it.value();
        if (!ifs.contains(kPart) || !ifs.contains(kBlock))
            continue;
        const QString table = pathOf(ifs.value(kPart).value(QStringLiteral("Table")));
        for (Drive &d : byDrive) {
            if (d.block != table)
                continue;
            Part p;
            p.block = it.key().path();
            p.label = ifs.value(kBlock).value(QStringLiteral("IdLabel")).toString();
            p.type = ifs.value(kBlock).value(QStringLiteral("IdType")).toString();
            if (ifs.contains(kFs))
                p.mounts = mountPoints(ifs.value(kFs).value(QStringLiteral("MountPoints")));
            d.parts << p;
        }
    }
    for (const Drive &d : std::as_const(byDrive))
        if (!d.block.isEmpty())
            out << d;
    return out;
}

const UsbManager::Drive *UsbManager::find(const QString &id) const
{
    for (const Drive &d : m_found)
        if (d.id == id)
            return &d;
    return nullptr;
}

QString UsbManager::ensureMounted(const Part &p) const
{
    if (!p.mounts.isEmpty())
        return p.mounts.first();
    const QDBusMessage r = call(p.block, kFs, QStringLiteral("Mount"), {quiet()});
    if (r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty())
        return r.arguments().first().toString();
    return {};
}

void UsbManager::readVentoyInfo(const Part &efi, QString *version, bool *secureBoot) const
{
    QVariantMap ro = quiet();
    ro.insert(QStringLiteral("options"), QStringLiteral("ro"));
    QString mount = efi.mounts.value(0);
    bool mountedHere = false;
    if (mount.isEmpty()) {
        const QDBusMessage r = call(efi.block, kFs, QStringLiteral("Mount"), {ro});
        if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
            return;
        mount = r.arguments().first().toString();
        mountedHere = true;
    }
    QFile cfg(mount + QStringLiteral("/grub/grub.cfg"));
    if (cfg.open(QIODevice::ReadOnly)) {
        const QRegularExpressionMatch m = QRegularExpression(QStringLiteral("VENTOY_VERSION=\"?([0-9][0-9.]*)")).match(QString::fromUtf8(cfg.readAll()));
        if (m.hasMatch())
            *version = m.captured(1);
    }
    *secureBoot = QFileInfo::exists(mount + QStringLiteral("/EFI/BOOT/grubx64_real.efi"));
    if (mountedHere)
        call(efi.block, kFs, QStringLiteral("Unmount"), {quiet()});
}

void UsbManager::refresh()
{
    if (m_busy)
        return;
    m_found = scan(nullptr);
    QVariantList list;
    for (const Drive &d : std::as_const(m_found)) {
        const Part *data = nullptr;
        const Part *efi = nullptr;
        for (const Part &p : d.parts) {
            if (p.label == QLatin1String("VTOYEFI"))
                efi = &p;
            else if (!data && p.type != QLatin1String("vfat") && p.label.compare(QLatin1String("Ventoy"), Qt::CaseInsensitive) == 0)
                data = &p;
        }
        if (!data && efi && !d.parts.isEmpty() && &d.parts.first() != efi)
            data = &d.parts.first();
        const bool ventoy = efi != nullptr && data != nullptr;
        QString version;
        bool secure = false;
        if (ventoy)
            readVentoyInfo(*efi, &version, &secure);

        QString mount;
        if (ventoy)
            mount = ensureMounted(*data);
        else
            for (const Part &p : d.parts)
                if (!p.mounts.isEmpty()) { mount = p.mounts.first(); break; }

        QVariantList images;
        double used = -1;
        QString usage = d.model;
        if (!mount.isEmpty()) {
            const QStorageInfo si(mount);
            if (si.bytesTotal() > 0) {
                used = 1.0 - double(si.bytesAvailable()) / si.bytesTotal();
                usage = QStringLiteral("%1 used of %2").arg(System::formatSize(si.bytesTotal() - si.bytesAvailable()), System::formatSize(si.bytesTotal()));
            }
            if (ventoy) {
                QDirIterator it(mount, {QStringLiteral("*.iso"), QStringLiteral("*.img"), QStringLiteral("*.wim"), QStringLiteral("*.vhd"),
                                        QStringLiteral("*.vhdx"), QStringLiteral("*.efi")},
                                QDir::Files, QDirIterator::Subdirectories);
                while (it.hasNext() && images.size() < 200) {
                    it.next();
                    if (it.filePath().contains(QLatin1String("/ventoy/")))
                        continue;
                    images << QVariantMap{{QStringLiteral("name"), QDir(mount).relativeFilePath(it.filePath())},
                                          {QStringLiteral("size"), System::formatSize(it.fileInfo().size())}};
                }
            }
        }
        list << QVariantMap{{QStringLiteral("id"), d.id},
                            {QStringLiteral("title"), d.title},
                            {QStringLiteral("model"), d.model.isEmpty() ? QStringLiteral("USB drive") : d.model},
                            {QStringLiteral("ventoy"), ventoy},
                            {QStringLiteral("ventoyVersion"), version},
                            {QStringLiteral("secureBoot"), secure},
                            {QStringLiteral("mounted"), !mount.isEmpty()},
                            {QStringLiteral("dataUrl"), mount.isEmpty() ? QString() : QUrl::fromLocalFile(mount).toString()},
                            {QStringLiteral("usedFraction"), used},
                            {QStringLiteral("usage"), usage},
                            {QStringLiteral("images"), images}};
    }
    m_drives = list;

    QVariantList isos;
    const QDir dl(QStandardPaths::writableLocation(QStandardPaths::DownloadLocation));
    const QFileInfoList files = dl.entryInfoList({QStringLiteral("*.iso"), QStringLiteral("*.img"), QStringLiteral("*.img.xz"),
                                                 QStringLiteral("*.img.bz2"), QStringLiteral("*.img.gz")},
                                                QDir::Files, QDir::Time);
    for (const QFileInfo &fi : files)
        isos << QVariantMap{{QStringLiteral("name"), fi.fileName()},
                            {QStringLiteral("size"), System::formatSize(fi.size())},
                            {QStringLiteral("url"), QUrl::fromLocalFile(fi.absoluteFilePath()).toString()}};
    m_isos = isos;
    Q_EMIT drivesChanged();
}

// ---------------------------------------------------------------- one-time setup

void UsbManager::setupRule()
{
    QString user = qEnvironmentVariable("USER");
    if (user.isEmpty())
        user = QDir::home().dirName();
    begin(QStringLiteral("Waiting for your password…"));
    QByteArray out;
    const int code = System::runHost({QStringLiteral("pkexec"), QStringLiteral("/bin/sh"), QStringLiteral("-c"),
                                      QStringLiteral("mkdir -p /etc/polkit-1/rules.d && install -m 0644 /dev/stdin ") + kRulePath},
                                     &out, ruleText(user).toUtf8(), 300000);
    if (code == 0) {
        markRule(true);
        Q_EMIT ruleChanged();
        end(true, QStringLiteral("Done. Huginn can now set up USB drives in Game Mode without asking."));
    } else if (code == 126) {
        end(false, QStringLiteral("Setup was cancelled. Nothing changed."));
    } else {
        end(false, QStringLiteral("The password didn't work. If you never set one, open Konsole, type passwd, then try again."));
    }
}

void UsbManager::removeRule()
{
    begin(QStringLiteral("Waiting for your password…"));
    const int code = System::runHost({QStringLiteral("pkexec"), QStringLiteral("rm"), QStringLiteral("-f"), kRulePath}, nullptr, {}, 300000);
    if (code == 0) {
        markRule(false);
        Q_EMIT ruleChanged();
        end(true, QStringLiteral("Removed. Ventoy and image writing need the setup again."));
    } else {
        end(false, QStringLiteral("Nothing was removed."));
    }
}

// ---------------------------------------------------------------- raw jobs

bool UsbManager::unmountAll(const Drive &d, QString *error)
{
    for (const Part &p : d.parts) {
        if (p.mounts.isEmpty())
            continue;
        const QDBusMessage r = call(p.block, kFs, QStringLiteral("Unmount"), {quiet()});
        if (r.type() == QDBusMessage::ErrorMessage) {
            *error = QStringLiteral("Couldn't close %1: %2. Close anything using the drive and try again.").arg(p.label.isEmpty() ? QStringLiteral("a partition") : p.label, r.errorMessage());
            return false;
        }
    }
    return true;
}

int UsbManager::openDevice(const QString &block, QString *error)
{
    QDBusMessage m = QDBusMessage::createMethodCall(kService, block, kBlock, QStringLiteral("OpenDevice"));
    m.setArguments({QStringLiteral("rw"), QVariantMap{{QStringLiteral("flags"), int(O_EXCL | O_DIRECT | O_CLOEXEC)}}});
    m.setInteractiveAuthorizationAllowed(true);   // without the setup rule, Desktop Mode can still ask once
    const QDBusMessage r = QDBusConnection::systemBus().call(m, QDBus::Block, 300000);
    if (r.type() == QDBusMessage::ErrorMessage) {
        if (r.errorName().contains(QLatin1String("NotAuthorized"))) {
            markRule(false);
            Q_EMIT ruleChanged();
            *error = QStringLiteral("The drive couldn't be opened without a password. Run the one-time setup in Desktop Mode.");
        } else {
            *error = r.errorMessage();
        }
        return -1;
    }
    const QDBusUnixFileDescriptor fd = qvariant_cast<QDBusUnixFileDescriptor>(r.arguments().value(0));
    if (!fd.isValid()) {
        *error = QStringLiteral("The drive service didn't hand over the drive.");
        return -1;
    }
    return ::dup(fd.fileDescriptor());   // dup drops close-on-exec, so the helper inherits it
}

void UsbManager::begin(const QString &status)
{
    m_busy = true;
    m_failed = false;
    m_progress = 0;
    m_status = status;
    m_log.clear();
    Q_EMIT progressChanged();
}

void UsbManager::end(bool ok, const QString &message)
{
    m_busy = false;
    m_failed = !ok;
    m_progress = ok ? 1 : m_progress;
    m_status = message;
    Q_EMIT progressChanged();
    Q_EMIT finished(ok, message);
}

void UsbManager::runHelper(const Drive &d, const QStringList &args, const QString &doneText)
{
    QString error;
    if (!unmountAll(d, &error)) {
        end(false, error);
        return;
    }
    m_fd = openDevice(d.block, &error);
    if (m_fd < 0) {
        end(false, error);
        return;
    }
    m_doneText = doneText;
    m_helperError.clear();
    auto *p = new QProcess(this);
    m_helper = p;
    p->setProcessChannelMode(QProcess::SeparateChannels);
    QStringList full{helperPath(), args.first(), QStringLiteral("--fd"), QString::number(m_fd), QStringLiteral("--name"), d.title};
    full << args.mid(1);
    connect(p, &QProcess::readyReadStandardOutput, this, [this, p] {
        while (p->canReadLine()) {
            const QJsonObject o = QJsonDocument::fromJson(p->readLine()).object();
            if (o.contains(QStringLiteral("stage"))) {
                static const QHash<QString, QString> words = {
                    {QStringLiteral("lookup"), QStringLiteral("Checking for the newest Ventoy…")},
                    {QStringLiteral("download"), QStringLiteral("Downloading…")},
                    {QStringLiteral("verify"), QStringLiteral("Checking the download…")},
                    {QStringLiteral("write"), QStringLiteral("Writing to the drive…")},
                    {QStringLiteral("check"), QStringLiteral("Reading it back to confirm…")},
                };
                const QString stage = o.value(QStringLiteral("stage")).toString();
                m_status = words.value(stage, m_status);
                const double total = o.value(QStringLiteral("total")).toDouble();
                m_progress = total > 0 ? o.value(QStringLiteral("done")).toDouble() / total : 0;
            } else if (o.contains(QStringLiteral("log"))) {
                m_log = o.value(QStringLiteral("log")).toString();
            } else if (o.contains(QStringLiteral("error"))) {
                m_helperError = o.value(QStringLiteral("error")).toString();
            }
            Q_EMIT progressChanged();
        }
    });
    connect(p, &QProcess::finished, this, [this, p, d](int code, QProcess::ExitStatus st) {
        ::close(m_fd);
        m_fd = -1;
        p->deleteLater();
        const bool ok = st == QProcess::NormalExit && code == 0;
        QString why = m_helperError;
        if (!ok && why.isEmpty())
            why = QString::fromUtf8(p->readAllStandardError()).trimmed().split(QLatin1Char('\n')).value(0);
        // Let udisks see the new partitions.
        call(d.block, kBlock, QStringLiteral("Rescan"), {quiet()});
        end(ok, ok ? m_doneText : (why.isEmpty() ? QStringLiteral("The drive job stopped unexpectedly.") : why));
        QTimer::singleShot(1500, this, &UsbManager::refresh);
    });
    p->start(QStringLiteral("python3"), full);
}

void UsbManager::installVentoy(const QString &id, bool secureBoot)
{
    const Drive *d = find(id);
    if (!d || m_busy)
        return;
    begin(QStringLiteral("Getting ready…"));
    runHelper(*d, {QStringLiteral("ventoy-install"), QStringLiteral("--secure-boot"), secureBoot ? QStringLiteral("1") : QStringLiteral("0")},
              QStringLiteral("Ventoy is installed. Copy ISO files onto the drive and boot from it."));
}

void UsbManager::updateVentoy(const QString &id)
{
    const Drive *d = find(id);
    if (!d || m_busy)
        return;
    begin(QStringLiteral("Getting ready…"));
    runHelper(*d, {QStringLiteral("ventoy-update")}, QStringLiteral("Ventoy is updated. Your ISO files are untouched."));
}

void UsbManager::writeImage(const QString &id, const QString &imageUrl)
{
    const Drive *d = find(id);
    if (!d || m_busy)
        return;
    const QString path = QUrl(imageUrl).isLocalFile() ? QUrl(imageUrl).toLocalFile() : imageUrl;
    if (!QFileInfo(path).isFile()) {
        Q_EMIT finished(false, QStringLiteral("Huginn can't find that image file."));
        return;
    }
    begin(QStringLiteral("Getting ready…"));
    runHelper(*d, {QStringLiteral("write-image"), QStringLiteral("--image"), path},
              QStringLiteral("%1 is written and checked.").arg(QFileInfo(path).fileName()));
}

void UsbManager::formatDrive(const QString &id)
{
    const Drive *found = find(id);
    if (!found || m_busy)
        return;
    const Drive d = *found;
    begin(QStringLiteral("Formatting…"));
    QString error;
    if (!unmountAll(d, &error)) {
        end(false, error);
        return;
    }
    // Both steps are allowed for removable drives without a password.
    QDBusMessage m = QDBusMessage::createMethodCall(kService, d.block, kBlock, QStringLiteral("Format"));
    m.setArguments({QStringLiteral("dos"), quiet()});
    auto *w = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(m, 300000), this);
    connect(w, &QDBusPendingCallWatcher::finished, this, [this, d, w] {
        w->deleteLater();
        if (w->isError()) {
            end(false, w->error().message());
            return;
        }
        m_progress = 0.4;
        Q_EMIT progressChanged();
        QVariantMap fsOpts = quiet();
        fsOpts.insert(QStringLiteral("label"), QStringLiteral("USB"));
        QDBusMessage c = QDBusMessage::createMethodCall(kService, d.block, kTable, QStringLiteral("CreatePartitionAndFormat"));
        c.setArguments({QVariant::fromValue<qulonglong>(1ULL << 20), QVariant::fromValue<qulonglong>(0), QStringLiteral("0x07"), QString(),
                        quiet(), QStringLiteral("exfat"), fsOpts});
        auto *w2 = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(c, 900000), this);
        connect(w2, &QDBusPendingCallWatcher::finished, this, [this, w2] {
            w2->deleteLater();
            end(!w2->isError(), w2->isError() ? w2->error().message() : QStringLiteral("Formatted as exFAT. It works on Windows, Mac and Linux."));
            QTimer::singleShot(1000, this, &UsbManager::refresh);
        });
    });
}

void UsbManager::eject(const QString &id)
{
    const Drive *d = find(id);
    if (!d || m_busy)
        return;
    QString error;
    if (!unmountAll(*d, &error)) {
        Q_EMIT finished(false, error);
        return;
    }
    const QDBusMessage r = call(d->id, kDrive, QStringLiteral("PowerOff"), {quiet()});
    Q_EMIT finished(r.type() != QDBusMessage::ErrorMessage,
                    r.type() != QDBusMessage::ErrorMessage ? QStringLiteral("Safe to unplug.") : QStringLiteral("Closed the drive. It's safe to unplug."));
    QTimer::singleShot(800, this, &UsbManager::refresh);
}

void UsbManager::cancel()
{
    if (m_helper)
        ::kill(m_helper->processId(), SIGTERM);
}

QVariantMap UsbManager::checksumFor(const QString &imageUrl) const
{
    const QFileInfo img(QUrl(imageUrl).toLocalFile());
    const QDir dir = img.dir();
    const QStringList candidates{img.fileName() + QStringLiteral(".sha256"), img.fileName() + QStringLiteral(".sha256sum"),
                                 img.completeBaseName() + QStringLiteral(".sha256"), QStringLiteral("SHA256SUMS"),
                                 QStringLiteral("sha256sums.txt"), QStringLiteral("SHA256SUMS.txt"), QStringLiteral("sha256sum.txt")};
    static const QRegularExpression hex(QStringLiteral("\\b([0-9a-fA-F]{64})\\b"));
    for (const QString &c : candidates) {
        QFile f(dir.filePath(c));
        if (!f.open(QIODevice::ReadOnly) || f.size() > 1024 * 1024)
            continue;
        const QStringList lines = QString::fromUtf8(f.readAll()).split(QLatin1Char('\n'));
        QString onlyHash;
        int hashes = 0;
        for (const QString &line : lines) {
            const QRegularExpressionMatch m = hex.match(line);
            if (!m.hasMatch())
                continue;
            ++hashes;
            onlyHash = m.captured(1);
            // "hash  name", "hash *name" or "SHA256 (name) = hash"
            if (line.contains(img.fileName()))
                return {{QStringLiteral("found"), true}, {QStringLiteral("expected"), m.captured(1).toLower()}, {QStringLiteral("source"), c}};
        }
        if (hashes == 1 && c.startsWith(img.fileName()))
            return {{QStringLiteral("found"), true}, {QStringLiteral("expected"), onlyHash.toLower()}, {QStringLiteral("source"), c}};
    }
    return {{QStringLiteral("found"), false}};
}

void UsbManager::verifyImage(const QString &imageUrl, const QString &expected)
{
    if (m_checking)
        return;
    const QString path = QUrl(imageUrl).toLocalFile();
    const qint64 total = QFileInfo(path).size();
    m_checking = true;
    m_checkProgress = 0;
    m_checkDone = std::make_shared<std::atomic<qint64>>(0);
    m_checkStop = std::make_shared<std::atomic<bool>>(false);
    Q_EMIT checkChanged();
    auto done = m_checkDone;
    auto stop = m_checkStop;
    m_checkTimer.setInterval(200);
    connect(&m_checkTimer, &QTimer::timeout, this, [this, total] {
        if (m_checkDone && total > 0) {
            m_checkProgress = double(m_checkDone->load()) / total;
            Q_EMIT checkChanged();
        }
    }, Qt::UniqueConnection);
    m_checkTimer.start();
    auto *watcher = new QFutureWatcher<QString>(this);
    connect(watcher, &QFutureWatcher<QString>::finished, this, [this, watcher, expected] {
        const QString got = watcher->result();
        watcher->deleteLater();
        m_checkTimer.stop();
        const bool stopped = m_checkStop && m_checkStop->load();
        m_checking = false;
        m_checkProgress = 0;
        Q_EMIT checkChanged();
        if (stopped)
            Q_EMIT checked(false, QStringLiteral("Check stopped. Nothing was written."));
        else if (got.isEmpty())
            Q_EMIT checked(false, QStringLiteral("Couldn't read the image to check it."));
        else if (got != expected.toLower())
            Q_EMIT checked(false, QStringLiteral("This download is damaged: it doesn't match its checksum. Download it again. Nothing was written."));
        else
            Q_EMIT checked(true, QStringLiteral("The download checked out."));
    });
    watcher->setFuture(QtConcurrent::run([path, done, stop]() -> QString {
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly))
            return {};
        QCryptographicHash h(QCryptographicHash::Sha256);
        QByteArray buf;
        while (!f.atEnd()) {
            if (stop->load())
                return QStringLiteral("stopped");
            buf = f.read(4 * 1024 * 1024);
            h.addData(buf);
            done->fetch_add(buf.size());
        }
        return QString::fromLatin1(h.result().toHex());
    }));
}

void UsbManager::cancelCheck()
{
    if (m_checkStop)
        m_checkStop->store(true);
}
