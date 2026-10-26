#include "archivemodel.h"
#include "fileops.h"
#include "foldermodel.h"
#include "gamepad.h"
#include "network.h"
#include "places.h"
#include "settings.h"
#include "steam.h"
#include "system.h"
#include "transfer.h"
#include "updater.h"
#include "usb.h"

#include <QApplication>
#include <QCommandLineParser>
#include <QDirIterator>
#include <QFont>
#include <QFontDatabase>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>

#ifndef HUGINN_VERSION
#define HUGINN_VERSION "0.1"
#endif

namespace
{
QtMessageHandler s_previous = nullptr;

// Counts QML warnings so the CI screenshot tour can fail the build on any of them.
void messages(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
    if (type >= QtWarningMsg) {
        const QString file = QString::fromUtf8(ctx.file ? ctx.file : "");
        if (file.endsWith(QLatin1String(".qml")) || msg.contains(QLatin1String(".qml:")) || msg.contains(QLatin1String("QML")))
            ++System::s_qmlWarnings;
    }
    if (s_previous)
        s_previous(type, ctx, msg);
}
}

int main(int argc, char *argv[])
{
    s_previous = qInstallMessageHandler(messages);
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("Huginn"));
    QApplication::setOrganizationName(QStringLiteral("Huginn"));
    QApplication::setApplicationVersion(QStringLiteral(HUGINN_VERSION));
    QApplication::setDesktopFileName(QStringLiteral("io.github.ravenor907.Huginn"));
    QQuickWindow::setTextRenderType(QQuickWindow::NativeTextRendering);

    QCommandLineParser args;
    args.addHelpOption();
    args.addVersionOption();
    QCommandLineOption tour(QStringLiteral("tour"), QStringLiteral("Open every screen, save a screenshot of each into DIR, then quit."), QStringLiteral("DIR"));
    QCommandLineOption windowed(QStringLiteral("windowed"), QStringLiteral("Run in a window instead of full screen."));
    args.addOption(tour);
    args.addOption(windowed);
    args.process(app);

    QDirIterator fonts(QStringLiteral(":/qt/qml/Huginn/fonts"), {QStringLiteral("*.ttf")}, QDir::Files);
    while (fonts.hasNext())
        QFontDatabase::addApplicationFont(fonts.next());
    QFont base(QStringLiteral("Barlow"));
    base.setPixelSize(17);
    QApplication::setFont(base);

    auto *settings = new Settings(&app);
    auto *system = new System(settings, args.value(tour), args.isSet(windowed) || args.isSet(tour), &app);
    auto *gamepad = new Gamepad(&app);
    auto *places = new Places(&app);
    auto *fileOps = new FileOps(settings, &app);
    auto *games = new GamesModel(&app);
    auto *usb = new UsbManager(&app);
    auto *transfer = new TransferServer(settings, &app);
    auto *network = new Network(settings, &app);
    auto *updater = new Updater(settings, &app);
    fileOps->setProtectedRoots(games->protectedRoots());

    qmlRegisterSingletonInstance("Huginn", 1, 0, "Settings", settings);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "System", system);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Gamepad", gamepad);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Places", places);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "FileOps", fileOps);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Games", games);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Usb", usb);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Transfer", transfer);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Network", network);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Updater", updater);
    qmlRegisterType<FolderModel>("Huginn", 1, 0, "FolderModel");
    qmlRegisterType<ArchiveModel>("Huginn", 1, 0, "ArchiveModel");

    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("qr"), new QrImageProvider);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(2); }, Qt::QueuedConnection);
    engine.loadFromModule("Huginn", "Main");
    return app.exec();
}
