#include "notify.h"

#include <QDBusInterface>
#include <QStringList>
#include <QVariantMap>

namespace Platform
{
void notify(const QString &title, const QString &body, int timeoutMs)
{
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"), title, body, QStringList(),
           QVariantMap(), timeoutMs);
}
}
