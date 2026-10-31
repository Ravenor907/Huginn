#pragma once

#include <QObject>
#include <QtGlobal>
#include <functional>

// Windows sleep and wake. The window can stay "active" across sleep, so Qt's application state
// doesn't always change; Windows still tells every window with WM_POWERBROADCAST. suspended runs
// as the machine goes to sleep (PBT_APMSUSPEND), resumed when it wakes (PBT_APMRESUMEAUTOMATIC,
// or PBT_APMRESUMESUSPEND after it). Both run on the GUI thread until context goes away.
// Linux has no watcher here: Qt's application state covers it.
namespace Platform
{
#ifdef Q_OS_WIN
void watchPower(QObject *context, std::function<void()> suspended, std::function<void()> resumed);
#else
inline void watchPower(QObject *, std::function<void()>, std::function<void()>) {}
#endif
}
