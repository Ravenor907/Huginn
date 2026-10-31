#pragma once

#include <QString>

class QQuickWindow;

// Starting up on Windows, and on Linux through Wine or Proton (Steam's "non-Steam game").
// Everything here does nothing on Linux builds.
namespace Platform
{
// True when this Windows build runs under Wine or Proton.
bool underWine();
QString wineVersion();   // "9.0" and so on; empty on Windows

// First thing in main(), before QApplication: opens Huginn-start.log next to Huginn.exe (the temp
// folder if that can't be written), logs every Qt warning and a crash into it, cleans up after an
// update, and under Wine picks Qt settings that work there. Nothing to undo.
void beginStart();
// Right after QApplication: picks a Qt Quick graphics backend that actually starts here (Direct3D 11
// on Windows, OpenGL under Wine, the software renderer if neither does), unless one was set already.
void chooseGraphics();
// A line in the start log ("engine loaded", "window shown"...).
void startStep(const QString &what);
// The main window: once it has drawn, the start went well and the log is deleted a few seconds later
// (kept when HUGINN_KEEP_START_LOG is set). If its graphics fail, Huginn restarts once with the
// software renderer instead of stopping with an error.
void watchMainWindow(QQuickWindow *window);
// Something stopped the start (the QML didn't load): keep the log and say where it is.
void startFailed(const QString &why);
// A command-line run (self-tests): no start log is left behind.
void noStartLog();
}
