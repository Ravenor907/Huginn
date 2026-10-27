#include "notify.h"
#include "singleinstance.h"

#include <QApplication>
#include <QIcon>
#include <QSystemTrayIcon>

namespace Platform
{
void notify(const QString &title, const QString &body, int timeoutMs)
{
    // One tray icon for the whole run, made the first time something needs to be said.
    static QSystemTrayIcon *tray = nullptr;
    if (!tray) {
        if (!QSystemTrayIcon::isSystemTrayAvailable())
            return;
        tray = new QSystemTrayIcon(QIcon(QStringLiteral(":/qt/qml/Huginn/assets/raven-gold.png")), qApp);
        tray->setToolTip(QStringLiteral("Huginn"));
        QObject::connect(tray, &QSystemTrayIcon::messageClicked, qApp, [] { SingleInstance::bringToFront(); });
        QObject::connect(tray, &QSystemTrayIcon::activated, qApp, [](QSystemTrayIcon::ActivationReason r) {
            if (r == QSystemTrayIcon::Trigger || r == QSystemTrayIcon::DoubleClick)
                SingleInstance::bringToFront();
        });
        tray->show();
    }
    tray->showMessage(title, body, tray->icon(), timeoutMs);
}
}
