#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>

class QSoundEffect;

// The raven call for a new message. Linux plays it with QSoundEffect. Windows plays it with the
// system's own PlaySound from memory: Qt's QSoundEffect can crash there when it is played
// (QTBUG-142694, Qt 6.8.3 to 6.10.1), and a notification chime needs nothing more.
class Chime : public QObject
{
public:
    Chime(const QString &resource, QObject *parent = nullptr);
    ~Chime() override;
    void play();
    bool ready() const;   // the sound loaded (for the self-test)

private:
    QByteArray m_wav;                 // Windows: the file itself, kept while it may be playing
    QSoundEffect *m_effect = nullptr; // Linux
};
