#pragma once

#include <QString>
#include <functional>

class QObject;

// Updates for the Windows portable build (Huginn-Windows.zip from the GitHub release).
// Updater downloads the zip through Tor exactly as it downloads the Flatpak, then calls install():
// the zip is unpacked into a staging folder beside Huginn.exe, and a small hidden PowerShell script
// waits for Huginn (and its tor.exe) to exit, copies the new files over the old ones, deletes the
// staging folder and starts the new Huginn.exe. When done(true, ...) arrives, quit Huginn.
// On other platforms (and in a Windows development build) supported() is false.
namespace WinUpdate
{
bool supported();
QString assetName();   // "Huginn-Windows.zip", the release asset to look for
// Empty when Huginn's folder can be written to, else a sentence for the person.
QString folderProblem();
// The zip keeps everything but Huginn.exe in lib\. After an update from an older zip (everything at
// the top), removes the old top-level copies of what lib\ now holds. Does nothing elsewhere.
void tidyOldLayout();
void install(const QString &zipPath, QObject *context, std::function<void(bool ok, const QString &message)> done);
}
