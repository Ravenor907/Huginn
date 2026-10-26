#include "archivemodel.h"
#include "fileops.h"
#include "foldermodel.h"
#include "gamepad.h"
#include "localsend.h"
#include "chat.h"
#include "direct.h"
#include "chatcrypto.h"
#include "ble.h"
#include "bleframes.h"
#include "nostr.h"
#include "photos.h"
#include "schnorr.h"
#include "vault.h"
#include "voice.h"
#include <QDBusInterface>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSoundEffect>
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
    QCommandLineOption nostrTest(QStringLiteral("nostr-selftest"), QStringLiteral("Check the Nostr signatures against Muninn's test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(nostrTest);
    QCommandLineOption privacyTest(QStringLiteral("privacy-selftest"), QStringLiteral("Check the vault, photo scrubbing and Bluetooth framing (for CI), then quit."));
    args.addOption(privacyTest);
    args.process(app);
    if (args.isSet(chatTest))
        return ChatCrypto::selfTest(args.value(chatTest)) ? 0 : 1;
    if (args.isSet(nostrTest))
        return Nostr::selfTest(args.value(nostrTest)) ? 0 : 1;
    if (args.isSet(privacyTest)) {
        const bool a = Vault::selfTest(), b = Photos::selfTest(), c = BleFrames::selfTest();
        return a && b && c ? 0 : 1;
    }
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
    // Bluetooth first: Qt decides how to reach BlueZ the first time anything asks (see Ble).
    Chat *chat = nullptr;
    auto *ble = new Ble([&chat] {
        if (!chat || !chat->ready())
            return QByteArray();
        return QJsonDocument(QJsonObject{{QStringLiteral("t"), QStringLiteral("hello")}, {QStringLiteral("name"), chat->username()},
                                         {QStringLiteral("chatKey"), chat->parseCode(chat->myCode()).value(QStringLiteral("key")).toString()},
                                         {QStringLiteral("mailbox"), chat->parseCode(chat->myCode()).value(QStringLiteral("mailbox")).toString()},
                                         {QStringLiteral("app"), QStringLiteral("huginn")}}).toJson(QJsonDocument::Compact);
    }, &app);
    auto *vault = new Vault(&app);
    if (args.isSet(tour) && !vault->exists())
        vault->setSecret(QStringLiteral("tour mode only"), false);   // CI screenshots: no lock to draw
    // The internet inbox's key lives in the vault; empty while locked (the inbox still says "new message").
    auto *nostr = new Nostr([vault] {
        if (vault->locked())
            return QByteArray();
        QJsonObject s = QJsonDocument::fromJson(vault->read(QStringLiteral("secrets.json"))).object();
        QByteArray secret = QByteArray::fromHex(s.value(QStringLiteral("nostrSecret")).toString().toLatin1());
        if (secret.size() != 32) {
            secret = Schnorr::newSecret();
            s.insert(QStringLiteral("nostrSecret"), QString::fromLatin1(secret.toHex()));
            vault->write(QStringLiteral("secrets.json"), QJsonDocument(s).toJson(QJsonDocument::Compact));
        }
        return secret;
    }, &app);
    chat = new Chat(localSend, vault, nostr, ble, &app);
    auto *voice = new Voice(chat, &app);
    if (!args.isSet(tour)) {
        // Stay reachable while Huginn runs: the relays (reconnecting by themselves) and Bluetooth.
        // Locked: say something came, once per message (replaced by Chat once unlocked).
        nostr->onEnvelope = [chat](const QString &) { Q_EMIT chat->notice(QStringLiteral("New message. Open Chat and unlock it to read.")); return false; };
        QObject::connect(vault, &Vault::opened, nostr, [nostr] { nostr->reconnectNow(); });
        nostr->setKeepAlive(true);
        nostr->connectRelays();
        ble->start();
    }
    // A message arrived: the raven call, and a desktop notification unless that chat is on screen.
    auto *sound = new QSoundEffect(&app);
    sound->setSource(QUrl(QStringLiteral("qrc:/qt/qml/Huginn/assets/raven_message.wav")));
    QObject::connect(chat, &Chat::arrived, &app, [chat, sound](const QString &name, const QString &text, const QString &key) {
        const bool looking = QGuiApplication::applicationState() == Qt::ApplicationActive && chat->property("openChat").toString() == key;
        if (looking)
            return;
        sound->play();
        if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
            const bool preview = chat->showPreview();
            QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"), QStringLiteral("org.freedesktop.Notifications"));
            n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"),
                   preview ? name : QStringLiteral("Huginn"), preview ? text : QStringLiteral("New message"), QStringList(), QVariantMap(), 6000);
        }
    });
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
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Vault", vault);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Voice", voice);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Ble", ble);
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
