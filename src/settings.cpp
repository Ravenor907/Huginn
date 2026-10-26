#include "settings.h"
#include <QUrl>
#include <QLocale>
#include <QFileInfo>
#include <QDateTime>

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

void Settings::rememberFolder(const QString &url)
{
    // Kept quietly (no general "changed" signal), so browsing never reloads the panes.
    QStringList list = recentFolders();
    list.removeAll(url);
    list.prepend(url);
    while (list.size() > 8)
        list.removeLast();
    m_settings.setValue("recentFolders", list);
    Q_EMIT recentFoldersChanged();
}

void Settings::rememberFile(const QString &url)
{
    QVariantList list = m_settings.value("recentFiles").toList();
    for (int i = list.size() - 1; i >= 0; --i)
        if (list.at(i).toMap().value(QStringLiteral("url")).toString() == url)
            list.removeAt(i);
    list.prepend(QVariantMap{{QStringLiteral("url"), url}, {QStringLiteral("at"), QDateTime::currentSecsSinceEpoch()}});
    while (list.size() > 30)
        list.removeLast();
    m_settings.setValue("recentFiles", list);
    Q_EMIT recentFilesChanged();
}

QVariantList Settings::recentFiles() const
{
    QVariantList out;
    const QDate today = QDate::currentDate();
    const QLocale en(QLocale::English);
    for (const QVariant &v : m_settings.value("recentFiles").toList()) {
        const QVariantMap m = v.toMap();
        const QUrl u(m.value(QStringLiteral("url")).toString());
        const QFileInfo fi(u.toLocalFile());
        if (!u.isLocalFile() || !fi.exists())
            continue;
        const QDateTime at = QDateTime::fromSecsSinceEpoch(m.value(QStringLiteral("at")).toLongLong());
        const QDate d = at.date();
        const QString when = d == today ? en.toString(at.time(), QStringLiteral("h:mm AP"))
                           : d == today.addDays(-1) ? QStringLiteral("Yesterday") : en.toString(d, QStringLiteral("MMM d"));
        out << QVariantMap{{QStringLiteral("url"), u.toString()}, {QStringLiteral("name"), fi.fileName()},
                           {QStringLiteral("folder"), QUrl::fromLocalFile(fi.absolutePath()).toString()},
                           {QStringLiteral("isDir"), fi.isDir()}, {QStringLiteral("when"), when}};
    }
    return out;
}
