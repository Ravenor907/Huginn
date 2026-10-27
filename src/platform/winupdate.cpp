#include "winupdate.h"

#include <QObject>

#ifdef Q_OS_WIN
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QProcess>
#include <QStandardPaths>
#include <QStringList>
#include <windows.h>
#endif

namespace WinUpdate
{
QString assetName()
{
    return QStringLiteral("Huginn-Windows.zip");
}

#ifndef Q_OS_WIN

bool supported() { return false; }
void tidyOldLayout() {}
QString folderProblem() { return {}; }
void install(const QString &, QObject *, std::function<void(bool, const QString &)> done)
{
    if (done)
        done(false, QStringLiteral("Updates from a zip are only for the Windows build."));
}

#else

namespace
{
// The portable folder people unzipped: the one with the launcher Huginn.exe. Huginn itself runs
// from its lib\ folder; an older zip had everything at the top.
QString appDir()
{
    const QDir d(QCoreApplication::applicationDirPath());
    if (d.dirName().compare(QLatin1String("lib"), Qt::CaseInsensitive) == 0 && QFileInfo::exists(d.absoluteFilePath(QStringLiteral("../Huginn.exe"))))
        return QDir::cleanPath(d.absoluteFilePath(QStringLiteral("..")));
    return d.absolutePath();
}
QString stagingDir() { return appDir() + QStringLiteral("/update-staging"); }

// PowerShell single-quoted literal: only ' needs doubling.
QString psQuote(const QString &s)
{
    QString n = QDir::toNativeSeparators(s);
    return QLatin1Char('\'') + n.replace(QLatin1Char('\''), QStringLiteral("''")) + QLatin1Char('\'');
}

// The folder in the zip that holds Huginn.exe: the staging folder itself or one level down (Huginn/).
QString payloadRoot(const QString &staging)
{
    if (QFileInfo::exists(staging + QStringLiteral("/Huginn.exe")))
        return staging;
    for (const QFileInfo &d : QDir(staging).entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot))
        if (QFileInfo::exists(d.absoluteFilePath() + QStringLiteral("/Huginn.exe")))
            return d.absoluteFilePath();
    return {};
}

bool writeScript(const QString &path, const QString &from)
{
    const QString log = QDir::tempPath() + QStringLiteral("/huginn-update.log");
    const QString script = QStringLiteral(
        "$ErrorActionPreference = 'SilentlyContinue'\r\n"
        "$src = %1\r\n"
        "$dst = %2\r\n"
        "$stage = %3\r\n"
        "$log = %4\r\n"
        "$huginn = %5\r\n"
        "# Wait for Huginn to close, then for its tor.exe (it exits by itself once Huginn is gone).\r\n"
        "Wait-Process -Id $huginn -Timeout 60\r\n"
        "$tor = Join-Path $dst 'tor.exe'\r\n"
        "$left = Get-Process -Name tor | Where-Object { $_.Path -eq $tor }\r\n"
        "if ($left) { $left | Wait-Process -Timeout 15; $left | Stop-Process -Force }\r\n"
        "& robocopy $src $dst /E /IS /IT /R:20 /W:1 /NP /NFL /NDL /LOG:$log | Out-Null\r\n"
        "$ok = $LASTEXITCODE -lt 8\r\n"
        "if ($ok) { Remove-Item -LiteralPath $stage -Recurse -Force }\r\n"
        "Start-Process -FilePath (Join-Path $dst 'Huginn.exe') -WorkingDirectory $dst\r\n"
        "Remove-Item -LiteralPath $PSCommandPath -Force\r\n")
        .arg(psQuote(from), psQuote(appDir()), psQuote(stagingDir()), psQuote(log), QString::number(QCoreApplication::applicationPid()));
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    // A byte order mark, so Windows PowerShell 5 reads non-English folder names correctly.
    f.write("\xEF\xBB\xBF");
    f.write(script.toUtf8());
    f.close();
    return f.error() == QFile::NoError;
}

bool startHidden(const QString &program, const QStringList &args)
{
    QProcess p;
    p.setProgram(program);
    p.setArguments(args);
    p.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *a) { a->flags |= CREATE_NO_WINDOW; });
    return p.startDetached();
}
}

bool supported()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    return true;   // only the CI-built zip; a development build updates by rebuilding
#else
    return false;
#endif
}

QString folderProblem()
{
    // Qt doesn't read Windows permissions by default, so just try writing.
    QFile probe(appDir() + QStringLiteral("/.huginn-write-test"));
    if (!probe.open(QIODevice::WriteOnly)) {
        return QStringLiteral("Huginn can't update itself where it is (%1). Move the Huginn folder somewhere of your own, "
                              "like Documents or the Desktop, and try again.")
            .arg(QDir::toNativeSeparators(appDir()));
    }
    probe.close();
    probe.remove();
    return {};
}

void tidyOldLayout()
{
#ifdef HUGINN_WINDOWS_PORTABLE
    // Updating from a zip that had everything at the top leaves those files beside the new lib\.
    // Remove only what lib\ now has a copy of (same name), so nothing else in the folder is touched.
    const QString root = appDir(), lib = QCoreApplication::applicationDirPath();
    if (QDir(root) == QDir(lib))
        return;
    static const QStringList keep{QStringLiteral("Huginn.exe"), QStringLiteral("LICENSE.txt"), QStringLiteral("lib"), QStringLiteral("update-staging")};
    for (const QFileInfo &fi : QDir(root).entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot | QDir::Hidden)) {
        if (keep.contains(fi.fileName(), Qt::CaseInsensitive))
            continue;
        const QFileInfo twin(lib + QLatin1Char('/') + fi.fileName());
        if (!twin.exists() || twin.isDir() != fi.isDir())
            continue;
        if (fi.isDir())
            QDir(fi.absoluteFilePath()).removeRecursively();
        else
            QFile::remove(fi.absoluteFilePath());
    }
#endif
}

void install(const QString &zipPath, QObject *context, std::function<void(bool, const QString &)> done)
{
    const QString problem = folderProblem();
    if (!problem.isEmpty()) {
        done(false, problem);
        return;
    }
    const QString staging = stagingDir();
    QDir(staging).removeRecursively();
    if (!QDir().mkpath(staging)) {
        done(false, QStringLiteral("Couldn't make a folder for the update."));
        return;
    }
    // Windows 10 (1803) and later ship bsdtar, which unpacks zips; PowerShell covers anything older.
    const QString tar = qEnvironmentVariable("SystemRoot", QStringLiteral("C:\\Windows")) + QStringLiteral("\\System32\\tar.exe");
    auto *p = new QProcess(context);
    p->setProcessChannelMode(QProcess::MergedChannels);
    if (QFileInfo::exists(tar)) {
        p->setProgram(tar);
        p->setArguments({QStringLiteral("-xf"), QDir::toNativeSeparators(zipPath), QStringLiteral("-C"), QDir::toNativeSeparators(staging)});
    } else {
        p->setProgram(QStringLiteral("powershell.exe"));
        p->setArguments({QStringLiteral("-NoProfile"), QStringLiteral("-NonInteractive"), QStringLiteral("-Command"),
                         QStringLiteral("Expand-Archive -LiteralPath %1 -DestinationPath %2 -Force").arg(psQuote(zipPath), psQuote(staging))});
    }
    p->setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *a) { a->flags |= CREATE_NO_WINDOW; });
    QObject::connect(p, &QProcess::finished, context, [p, zipPath, staging, done](int code, QProcess::ExitStatus st) {
        p->deleteLater();
        const QString root = payloadRoot(staging);
        if (st != QProcess::NormalExit || code != 0 || root.isEmpty()) {
            qWarning("update: unpacking failed (%d): %s", code, p->readAll().constData());
            QDir(staging).removeRecursively();
            done(false, QStringLiteral("The update couldn't be unpacked. Try again."));
            return;
        }
        QFile::remove(zipPath);
        const QString script = QDir::tempPath() + QStringLiteral("/huginn-update-%1.ps1").arg(QCoreApplication::applicationPid());
        if (!writeScript(script, root)
            || !startHidden(QStringLiteral("powershell.exe"),
                            {QStringLiteral("-NoProfile"), QStringLiteral("-NonInteractive"), QStringLiteral("-ExecutionPolicy"), QStringLiteral("Bypass"),
                             QStringLiteral("-WindowStyle"), QStringLiteral("Hidden"), QStringLiteral("-File"), QDir::toNativeSeparators(script)})) {
            done(false, QStringLiteral("Couldn't start the update."));
            return;
        }
        SingleInstance::releaseForRestart();
        done(true, QStringLiteral("Updated. Restarting…"));
    });
    QObject::connect(p, &QProcess::errorOccurred, context, [p, done](QProcess::ProcessError e) {
        if (e != QProcess::FailedToStart)
            return;
        p->deleteLater();
        done(false, QStringLiteral("Windows couldn't unpack the update."));
    });
    p->start();
}

#endif
}
