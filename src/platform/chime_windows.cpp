#include "chime.h"

#include <QFile>

#include <windows.h>
#include <mmsystem.h>

Chime::Chime(const QString &resource, QObject *parent)
    : QObject(parent)
{
    QFile f(resource);
    if (f.open(QIODevice::ReadOnly))
        m_wav = f.readAll();
}

Chime::~Chime()
{
    if (!m_wav.isEmpty())
        PlaySoundW(nullptr, nullptr, 0);   // stop before the bytes go away
}

void Chime::play()
{
    if (m_wav.isEmpty())
        return;
    // Asynchronous: returns at once. A new call cuts off one still playing, which is fine for a chime.
    PlaySoundW(reinterpret_cast<LPCWSTR>(m_wav.constData()), nullptr, SND_MEMORY | SND_ASYNC | SND_NODEFAULT);
}

bool Chime::ready() const
{
    return m_wav.startsWith("RIFF");
}
