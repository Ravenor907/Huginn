#include "notify.h"
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusMessage>
#include <QHash>
#include <QObject>
#include <QStringList>
#include <QVariantMap>

namespace Platform
{
// Clicked notifications: the desktop says which one with ActionInvoked ("default" is a click on it).
class NotifyClicks : public QObject
{
    Q_OBJECT

public:
    QHash<uint, std::function<void()>> pending;

public Q_SLOTS:
    void invoked(uint id, const QString &action)
    {
        const std::function<void()> f = pending.take(id);
        if (!f || action != QLatin1String("default"))
            return;
        SingleInstance::bringToFront();
        f();
    }
    void closed(uint id, uint reason)
    {
        Q_UNUSED(reason);
        pending.remove(id);
    }
};

namespace
{
NotifyClicks *clicks()
{
    static NotifyClicks *c = nullptr;
    if (!c) {
        c = new NotifyClicks;
        c->setParent(QCoreApplication::instance());
        QDBusConnection bus = QDBusConnection::sessionBus();
        const QString service = QStringLiteral("org.freedesktop.Notifications"), path = QStringLiteral("/org/freedesktop/Notifications");
        bus.connect(service, path, service, QStringLiteral("ActionInvoked"), c, SLOT(invoked(uint,QString)));
        bus.connect(service, path, service, QStringLiteral("NotificationClosed"), c, SLOT(closed(uint,uint)));
    }
    return c;
}
}

void notify(const QString &title, const QString &body, int timeoutMs)
{
    notify(title, body, timeoutMs, nullptr);
}

void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick)
{
    NotifyClicks *c = onClick ? clicks() : nullptr;
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    const QStringList actions = onClick ? QStringList{QStringLiteral("default"), QStringLiteral("Open")} : QStringList();
    const QDBusMessage r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"), title, body,
                                  actions, QVariantMap(), timeoutMs);
    if (c && r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty()) {
        if (c->pending.size() > 50)
            c->pending.clear();
        c->pending.insert(r.arguments().constFirst().toUInt(), std::move(onClick));
    }
}
}

#include "notify_linux.moc"
