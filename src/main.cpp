#include "archivemodel.h"
#include "fileops.h"
#include "foldermodel.h"
#include "gamepad.h"
#include "localsend.h"
#include "chat.h"
#include "direct.h"
#include "chatcrypto.h"
#include "network.h"
#include "places.h"
#include "settings.h"
#include "steam.h"
#include "storage.h"
#include "system.h"
#include "transfer.h"
#include "updater.h"
#include "usb.h"

#include <QApplication>
#include <cstdio>
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
    QCommandLineOption unattend(QStringLiteral("unattend-sample"), QStringLiteral("Print the Windows setup file with every choice on (for CI), then quit."));
    args.addOption(unattend);
    QCommandLineOption chatTest(QStringLiteral("chat-selftest"), QStringLiteral("Check the chat encryption against Muninn's test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(chatTest);
    args.process(app);
    if (args.isSet(chatTest))
        return ChatCrypto::selfTest(args.value(chatTest)) ? 0 : 1;
    if (args.isSet(unattend)) {
        const QVariantMap all{{QStringLiteral("bypass"), true}, {QStringLiteral("noOnline"), true}, {QStringLiteral("user"), QStringLiteral("Player")},
                              {QStringLiteral("regional"), true}, {QStringLiteral("noData"), true}, {QStringLiteral("noBitlocker"), true}};
        fputs(UsbManager::unattendXml(all, false).toUtf8().constData(), stdout);
        return 0;
    }

    QDirIterator fonts(QStringLiteral(":/qt/qml/Huginn/fonts"), {QStringLiteral("*.ttf")}, QDir::Files);
    while (fonts.hasNext())
        QFontDatabase::addApplicationFont(fonts.next());
    QFont base(QStringLiteral("Barlow"));
    base.setPixelSize(17);
    QApplication::setFont(base);

    auto *settings = new Settings(&app);
    FolderModel::setSettings(settings);
    auto *system = new System(settings, args.value(tour), args.isSet(windowed) || args.isSet(tour), &app);
    auto *gamepad = new Gamepad(&app);
    auto *places = new Places(&app);
    auto *fileOps = new FileOps(settings, &app);
    auto *games = new GamesModel(&app);
    auto *usb = new UsbManager(&app);
    auto *transfer = new TransferServer(settings, &app);
    auto *network = new Network(settings, &app);
    auto *updater = new Updater(settings, &app);
    auto *storage = new Storage(&app);
    auto *localSend = new LocalSend(settings, &app);
    auto *chat = new Chat(localSend, &app);
    auto *direct = new Direct(&app);
    if (!args.isSet(tour)) {
        if (settings->nearbySharing())
            localSend->start();
        QObject::connect(settings, &Settings::changed, localSend, [settings, localSend] {
            if (settings->nearbySharing() != localSend->running())
                settings->nearbySharing() ? localSend->start() : localSend->stop();
        });
    }
    // Long jobs keep the handheld awake until they finish.
    auto awake = [system, fileOps, usb, localSend] {
        const bool on = fileOps->busy() || usb->busy() || localSend->busy();
        system->setKeepAwake(on, usb->busy() ? QStringLiteral("Writing a USB drive") : localSend->busy() ? QStringLiteral("Sending files")
                                                                                                          : QStringLiteral("Copying files"));
    };
    QObject::connect(fileOps, &FileOps::busyChanged, system, awake);
    QObject::connect(usb, &UsbManager::progressChanged, system, awake);
    QObject::connect(localSend, &LocalSend::progressChanged, system, awake);
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
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Storage", storage);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "LocalSend", localSend);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Chat", chat);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Direct", direct);
    qmlRegisterType<FolderModel>("Huginn", 1, 0, "FolderModel");
    qmlRegisterType<ArchiveModel>("Huginn", 1, 0, "ArchiveModel");

    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("qr"), new QrImageProvider);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(2); }, Qt::QueuedConnection);
    engine.loadFromModule("Huginn", "Main");
    if (!args.isSet(tour))
        updater->startAutoUpdate();
    return app.exec();
}
