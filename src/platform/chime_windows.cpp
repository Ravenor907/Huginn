#include "chime.h"

#include <QDirIterator>
#include <QFile>
#include <QFileInfo>

#include <windows.h>
#include <mmsystem.h>

Chime::Chime(const QString &resource, QObject *parent)
    : QObject(parent)
{
    QFile f(resource);
    if (f.open(QIODevice::ReadOnly))
        m_wav = f.readAll();
    if (m_wav.isEmpty()) {
        // Wherever the build put it in the resources.
        const QString name = QFileInfo(resource).fileName();
        QDirIterator it(QStringLiteral(":/"), {name}, QDir::Files, QDirIterator::Subdirectories);
        if (it.hasNext()) {
            QFile g(it.next());
            if (g.open(QIODevice::ReadOnly))
                m_wav = g.readAll();
        }
    }
    if (!ready())
        qWarning("raven call: %s couldn't be read (%d bytes)", qPrintable(resource), int(m_wav.size()));
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
