#include "settings.h"
#include <QDir>
#include <QStandardPaths>

bool Settings::s_quiet = false;

Settings::Settings(QObject *parent)
    : QObject(parent)
    , m_settings(QStringLiteral("Huginn"), QStringLiteral("Huginn"))
{
}

QString Settings::transferFolder() const
{
    const QString fallback = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    const QString v = value("transferFolder", fallback).toString();
    return v.isEmpty() ? QDir::homePath() : v;
}

// Do not disturb: kept in memory, set from your status (Main.qml binds it to Profile.myStatus).
void Settings::setQuiet(bool v)
{
    if (s_quiet == v)
        return;
    s_quiet = v;
    Q_EMIT changed();
}

void Settings::set(const QString &key, const QVariant &v)
{
    if (m_settings.value(key) == v)
        return;
    m_settings.setValue(key, v);
    m_settings.sync();
    Q_EMIT changed();
}

// Appearance: saved the same way, told only to what draws the screen.
void Settings::setLook(const QString &key, const QVariant &v)
{
    if (m_settings.value(key) == v)
        return;
    m_settings.setValue(key, v);
    m_settings.sync();
    Q_EMIT appearanceChanged();
}
