#include "chime.h"

#include <QSoundEffect>
#include <QUrl>

Chime::Chime(const QString &resource, QObject *parent)
    : QObject(parent)
    , m_effect(new QSoundEffect(this))
{
    // ":/x" is "qrc:/x" to QSoundEffect.
    m_effect->setSource(QUrl(QStringLiteral("qrc") + resource));
}

Chime::~Chime() = default;

void Chime::play()
{
    m_effect->play();
}

void Chime::stop()
{
    m_effect->stop();
}

bool Chime::ready() const
{
    return m_effect->status() != QSoundEffect::Error;
}
