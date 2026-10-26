#pragma once

#include <QFutureWatcher>
#include <QObject>
#include <QVariantList>

// "What's using space": each drive's free space, and what fills it, biggest first.
// Measuring runs in the background; cleanups go to the trash or empty it.
class Storage : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList drives READ drives NOTIFY changed)
    Q_PROPERTY(bool measuring READ measuring NOTIFY changed)

public:
    explicit Storage(QObject *parent = nullptr);

    QVariantList drives() const { return m_drives; }
    bool measuring() const { return m_watcher.isRunning(); }

    Q_INVOKABLE void refresh();
    Q_INVOKABLE void clearShaderCaches();
    Q_INVOKABLE void emptyTrash();

    static QVariantList measure();

Q_SIGNALS:
    void changed();
    void finished(bool ok, const QString &message);

private:
    QVariantList m_drives;
    QFutureWatcher<QVariantList> m_watcher;
};
