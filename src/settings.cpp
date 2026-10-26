#include "settings.h"

#include <QDir>
#include <QStandardPaths>

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

void Settings::set(const QString &key, const QVariant &v)
{
    if (m_settings.value(key) == v)
        return;
    m_settings.setValue(key, v);
    m_settings.sync();
    Q_EMIT changed();
}

QVariantList Settings::shares() const
{
    return m_settings.value("shares").toList();
}

void Settings::setShares(const QVariantList &list)
{
    m_settings.setValue("shares", list);
    m_settings.sync();
    Q_EMIT changed();
}

void Settings::rememberDestination(const QString &url)
{
    QStringList list = recentDestinations();
    list.removeAll(url);
    list.prepend(url);
    while (list.size() > 6)
        list.removeLast();
    set("recentDestinations", list);
}
