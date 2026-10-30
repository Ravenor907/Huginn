#include "winstart.h"

#ifndef Q_OS_WIN

namespace Platform
{
bool underWine() { return false; }
QString wineVersion() { return {}; }
void beginStart() {}
void chooseGraphics() {}
void startStep(const QString &) {}
void watchMainWindow(QQuickWindow *) {}
void startFailed(const QString &) {}
void noStartLog() {}
}

#else

#include "singleinstance.h"
#include "winupdate.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QMutex>
#include <QOffscreenSurface>
#include <QProcess>
#include <QProcessEnvironment>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QTimer>
#include <rhi/qrhi.h>

#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>

#ifndef HUGINN_VERSION
#define HUGINN_VERSION "0.1"
#endif

namespace
{
QString s_logPath;
FILE *s_log = nullptr;
QMutex s_logLock;
QtMessageHandler s_next = nullptr;
bool s_keep = false;
bool s_shown = false;

QString exeDir()
{
    std::wstring self(32768, L'\0');
    const DWORD n = GetModuleFileNameW(nullptr, self.data(), DWORD(self.size()));
    if (n == 0 || n >= self.size())
        return {};
    self.resize(n);
    return QFileInfo(QDir::fromNativeSeparators(QString::fromStdWString(self))).absolutePath();
}

void writeLine(const QByteArray &line)
{
    QMutexLocker lock(&s_logLock);
    if (!s_log)
        return;
    std::fputs(line.constData(), s_log);
    std::fputc('\n', s_log);
    std::fflush(s_log);   // a hang or a crash must not lose the last lines
}

void logLine(const QString &what)
{
    writeLine(QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz ")).toUtf8() + what.toUtf8());
}

void messages(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
    if (type != QtDebugMsg) {
        static const char *names[] = {"debug", "warning", "critical", "fatal", "info"};
        logLine(QString::fromLatin1(names[qBound(0, int(type), 4)]) + QStringLiteral(": ") + msg);
    }
    if (s_next)
        s_next(type, ctx, msg);
}

// A crash: what and where, so the log says more than "it stopped".
LONG WINAPI crashed(EXCEPTION_POINTERS *e)
{
    char line[512];
    const void *at = e && e->ExceptionRecord ? e->ExceptionRecord->ExceptionAddress : nullptr;
    HMODULE mod = nullptr;
    char name[MAX_PATH] = "?";
    if (at && GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, static_cast<LPCSTR>(at), &mod))
        GetModuleFileNameA(mod, name, MAX_PATH);
    std::snprintf(line, sizeof line, "crash: exception 0x%08lx at %s+0x%llx",
                  e && e->ExceptionRecord ? e->ExceptionRecord->ExceptionCode : 0ul, name,
                  static_cast<unsigned long long>(reinterpret_cast<quintptr>(at) - reinterpret_cast<quintptr>(mod)));
    writeLine(line);
    // The calls that led there (module+offset each), unwound from the crash's own registers.
    if (e && e->ContextRecord) {
        CONTEXT c = *e->ContextRecord;
        for (int i = 0; i < 32 && c.Rip; ++i) {
            HMODULE m = nullptr;
            char mname[MAX_PATH] = "?";
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, reinterpret_cast<LPCSTR>(c.Rip), &m))
                GetModuleFileNameA(m, mname, MAX_PATH);
            const char *base = std::strrchr(mname, '\\');
            std::snprintf(line, sizeof line, "  at %s+0x%llx", base ? base + 1 : mname,
                          static_cast<unsigned long long>(c.Rip - reinterpret_cast<quintptr>(m)));
            writeLine(line);
            DWORD64 imageBase = 0;
            PRUNTIME_FUNCTION fn = RtlLookupFunctionEntry(c.Rip, &imageBase, nullptr);
            if (!fn) {
                // A leaf function: the return address is on top of the stack.
                c.Rip = *reinterpret_cast<DWORD64 *>(c.Rsp);
                c.Rsp += 8;
                continue;
            }
            void *handlerData = nullptr;
            DWORD64 frame = 0;
            RtlVirtualUnwind(UNW_FLAG_NHANDLER, imageBase, c.Rip, fn, &c, &handlerData, &frame, nullptr);
        }
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

void closeLog(bool remove)
{
    QMutexLocker lock(&s_logLock);
    if (s_log) {
        std::fclose(s_log);
        s_log = nullptr;
    }
    if (remove && !s_logPath.isEmpty())
        QFile::remove(s_logPath);
}

bool envSet(const char *name)
{
    return !qEnvironmentVariableIsEmpty(name);
}

QSGRendererInterface::GraphicsApi apiFor(QRhi::Implementation i)
{
    return i == QRhi::OpenGLES2 ? QSGRendererInterface::OpenGL : QSGRendererInterface::Direct3D11;
}

bool probe(QRhi::Implementation impl)
{
    if (impl == QRhi::OpenGLES2) {
        QRhiGles2InitParams params;
        params.fallbackSurface = QRhiGles2InitParams::newFallbackSurface();
        const bool ok = QRhi::probe(impl, &params);
        delete params.fallbackSurface;
        return ok;
    }
    QRhiD3D11InitParams params;
    return QRhi::probe(impl, &params);
}
}

namespace Platform
{
bool underWine()
{
    static const bool wine = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version") != nullptr;
    return wine;
}

QString wineVersion()
{
    using Fn = const char *(__cdecl *)();
    const auto fn = reinterpret_cast<Fn>(reinterpret_cast<void *>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version")));
    return fn ? QString::fromLatin1(fn()) : QString();
}

void beginStart()
{
    s_keep = envSet("HUGINN_KEEP_START_LOG");
    const QString dir = exeDir();
    for (const QString &where : {dir, QDir::tempPath()}) {
        if (where.isEmpty())
            continue;
        s_logPath = where + QStringLiteral("/Huginn-start.log");
        s_log = _wfopen(reinterpret_cast<const wchar_t *>(QDir::toNativeSeparators(s_logPath).utf16()), L"w");
        if (s_log)
            break;
    }
    s_next = qInstallMessageHandler(messages);
    SetUnhandledExceptionFilter(crashed);
    logLine(QStringLiteral("Huginn %1 starting in %2").arg(QStringLiteral(HUGINN_VERSION), QDir::toNativeSeparators(dir)));

    OSVERSIONINFOW os{};
    os.dwOSVersionInfoSize = sizeof os;
    using RtlGetVersionFn = LONG(WINAPI *)(OSVERSIONINFOW *);
    if (const auto rtl = reinterpret_cast<RtlGetVersionFn>(reinterpret_cast<void *>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion"))))
        rtl(&os);
    logLine(QStringLiteral("Windows %1.%2 build %3").arg(os.dwMajorVersion).arg(os.dwMinorVersion).arg(os.dwBuildNumber));

    // The DLLs are in lib\ (a private assembly, see packaging/windows/huginn.manifest.in). Qt looks for
    // OpenSSL's libssl by full path in Huginn's folder, the system folder and then PATH, so lib\ goes on PATH.
    const QString lib = dir + QStringLiteral("/lib");
    if (!dir.isEmpty() && QFileInfo::exists(lib + QStringLiteral("/lib.manifest")))
        qputenv("PATH", QDir::toNativeSeparators(lib).toLocal8Bit() + ';' + qgetenv("PATH"));

    if (underWine()) {
        logLine(QStringLiteral("running under Wine %1%2").arg(wineVersion(), envSet("STEAM_COMPAT_DATA_PATH") ? QStringLiteral(" (Proton)") : QString()));
        // Fonts through GDI, which Wine has had the longest, and no Windows dark-mode lookups:
        // Huginn draws its own dark theme anyway. Anyone who set QT_QPA_PLATFORM keeps their choice.
        logLine(QStringLiteral("QT_QPA_PLATFORM was \"%1\"").arg(qEnvironmentVariable("QT_QPA_PLATFORM")));
        if (!envSet("QT_QPA_PLATFORM"))
            qputenv("QT_QPA_PLATFORM", "windows:fontengine=gdi,darkmode=0");
    }
    // What an update moved aside and what older zips left at the top: before Qt loads any plugin.
    WinUpdate::cleanUp();
}

void chooseGraphics()
{
    // The copy that just updated us may still have been closing, holding its old Huginn.exe: once more in a while.
    QTimer::singleShot(15000, qApp, [] { WinUpdate::cleanUp(); });
    const QString platform = QGuiApplication::platformName();
    logLine(QStringLiteral("Qt %1, platform %2").arg(QString::fromLatin1(qVersion()), platform));
    if (envSet("QSG_RHI_BACKEND") || envSet("QT_QUICK_BACKEND")) {
        logLine(QStringLiteral("graphics: as set (QSG_RHI_BACKEND=%1, QT_QUICK_BACKEND=%2)")
                    .arg(qEnvironmentVariable("QSG_RHI_BACKEND"), qEnvironmentVariable("QT_QUICK_BACKEND")));
        return;
    }
    if (platform != QLatin1String("windows")) {
        // offscreen or minimal (CI): no screen to draw on, so the software renderer.
        QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
        logLine(QStringLiteral("graphics: software (no screen)"));
        return;
    }
    // Under Wine, OpenGL goes straight to the Linux driver; Direct3D is a translation on top.
    const QList<QRhi::Implementation> order = underWine() ? QList<QRhi::Implementation>{QRhi::OpenGLES2, QRhi::D3D11}
                                                          : QList<QRhi::Implementation>{QRhi::D3D11, QRhi::OpenGLES2};
    for (const QRhi::Implementation impl : order) {
        if (probe(impl)) {
            QQuickWindow::setGraphicsApi(apiFor(impl));
            logLine(QStringLiteral("graphics: %1").arg(impl == QRhi::OpenGLES2 ? QStringLiteral("OpenGL") : QStringLiteral("Direct3D 11")));
            return;
        }
        logLine(QStringLiteral("graphics: %1 didn't start").arg(impl == QRhi::OpenGLES2 ? QStringLiteral("OpenGL") : QStringLiteral("Direct3D 11")));
    }
    QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
    logLine(QStringLiteral("graphics: software"));
}

void startStep(const QString &what)
{
    logLine(what);
}

void watchMainWindow(QQuickWindow *window)
{
    if (!window) {
        startFailed(QStringLiteral("no main window"));
        return;
    }
    // No window could be made (no screen Windows or Wine can draw on): say so and stop, rather than
    // run on with nothing to see.
    QTimer::singleShot(0, window, [window] {
        if (!window->handle()) {
            startFailed(QStringLiteral("Windows couldn't make Huginn's window"));
            QCoreApplication::exit(5);
        }
    });
    // Drawn once: the start worked.
    auto *once = new QObject(window);
    QObject::connect(window, &QQuickWindow::frameSwapped, once, [once] {
        once->deleteLater();
        if (s_shown)
            return;   // more frames were already on their way
        logLine(QStringLiteral("window shown"));
        s_shown = true;
        QObject::connect(qApp, &QCoreApplication::aboutToQuit, qApp, [] { closeLog(!s_keep); });
        QTimer::singleShot(5000, qApp, [] {
            logLine(QStringLiteral("started"));
            closeLog(!s_keep);
        });
    }, Qt::QueuedConnection);
    // The graphics backend failed after all: start again once with the software renderer.
    QObject::connect(window, &QQuickWindow::sceneGraphError, qApp, [](QQuickWindow::SceneGraphError, const QString &msg) {
        logLine(QStringLiteral("graphics failed: ") + msg);
        if (envSet("HUGINN_GRAPHICS_RETRY")) {
            startFailed(QStringLiteral("graphics failed with the software renderer too"));
            QCoreApplication::exit(4);
            return;
        }
        SingleInstance::releaseForRestart();
        QProcess p;
        p.setProgram(QCoreApplication::applicationFilePath());
        p.setArguments(QCoreApplication::arguments().mid(1));
        QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
        env.insert(QStringLiteral("QT_QUICK_BACKEND"), QStringLiteral("software"));
        env.insert(QStringLiteral("HUGINN_GRAPHICS_RETRY"), QStringLiteral("1"));
        p.setProcessEnvironment(env);
        logLine(p.startDetached() ? QStringLiteral("restarting with the software renderer") : QStringLiteral("couldn't restart"));
        QCoreApplication::exit(4);
    });
}

void startFailed(const QString &why)
{
    logLine(QStringLiteral("start failed: ") + why);
    s_keep = true;
}

void noStartLog()
{
    closeLog(true);
}
}

#endif
