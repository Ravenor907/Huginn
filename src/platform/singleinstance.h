#pragma once

#include <QObject>
#include <QString>

class QLocalServer;
class QLockFile;

// One Huginn per user. A second copy (a double click on Huginn.exe while it already runs)
// asks the first one to come to the front and then quits, so two copies never fight over
// the chat folder or start two Tor processes on the same data directory.
// Works the same on Linux and Windows (a lock file in the app data folder plus a local socket).
class SingleInstance : public QObject
{
    Q_OBJECT

public:
    explicit SingleInstance(QObject *parent = nullptr);
    ~SingleInstance() override;

    // True: this is the only copy (keep going). False: another copy runs and was asked to show itself.
    bool claim();
    // Before starting a fresh copy of ourselves (restore, update): let it claim straight away.
    static void releaseForRestart();
    // Unminimises, raises and focuses Huginn's windows.
    static void bringToFront();

Q_SIGNALS:
    void activated();   // another copy was started

private:
    void release();
    QString m_name;
    QLockFile *m_lock = nullptr;
    QLocalServer *m_server = nullptr;
};
