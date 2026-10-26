#pragma once

#include <QObject>
#include <QPointer>
#include <QUrl>
#include <QVariantList>

class Settings;
class KJob;
namespace KIO { class ListJob; }

// Saved shares and the computers sharing folders nearby (found through KIO's smb:/ listing,
// which uses the same discovery as Dolphin's Network view).
class Network : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList saved READ saved NOTIFY changed)
    Q_PROPERTY(QVariantList found READ found NOTIFY changed)
    Q_PROPERTY(bool searching READ searching NOTIFY changed)

public:
    explicit Network(Settings *settings, QObject *parent = nullptr);

    QVariantList saved() const;
    QVariantList found() const { return m_found; }
    bool searching() const { return m_job != nullptr; }

    Q_INVOKABLE void discover();
    Q_INVOKABLE void save(const QString &url, const QString &name);
    Q_INVOKABLE void forget(const QString &url);
    Q_INVOKABLE QString normalize(const QString &text) const;

Q_SIGNALS:
    void changed();

private:
    static QVariantMap entry(const QUrl &url, const QString &name);
    Settings *m_settings;
    QPointer<KIO::ListJob> m_job;
    QVariantList m_found;
};
