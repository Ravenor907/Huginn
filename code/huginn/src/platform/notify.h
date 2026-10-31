#pragma once

#include <QString>

// A desktop notification ("New message"). Linux: org.freedesktop.Notifications over D-Bus.
// Windows: a tray icon balloon, which Windows 10 and 11 show as a normal toast.
namespace Platform
{
void notify(const QString &title, const QString &body, int timeoutMs = 6000);
}
