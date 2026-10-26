#include "network.h"
#include "settings.h"

#include <KIO/ListJob>

Network::Network(Settings *settings, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
{
}

QVariantMap Network::entry(const QUrl &url, const QString &name)
{
    QString kind = url.scheme() == QLatin1String("smb") ? QStringLiteral("Windows share") : url.scheme().toUpper();
    QString display = name.isEmpty() ? url.host() : name;
    if (display.isEmpty())
        display = url.toDisplayString();
    return {{QStringLiteral("url"), url.toString()},
            {QStringLiteral("name"), display},
            {QStringLiteral("address"), url.toDisplayString(QUrl::RemovePassword)},
            {QStringLiteral("kind"), kind}};
}

QVariantList Network::saved() const
{
    QVariantList out;
    for (const QVariant &v : m_settings->shares()) {
        const QVariantMap m = v.toMap();
        out << entry(QUrl(m.value(QStringLiteral("url")).toString()), m.value(QStringLiteral("name")).toString());
    }
    return out;
}

void Network::discover()
{
    // Windows share discovery needs Samba, which isn't in the 0.1 build.
    if (m_job || qEnvironmentVariableIsEmpty("HUGINN_SMB"))
        return;
    m_found.clear();
    m_job = KIO::listDir(QUrl(QStringLiteral("smb:/")), KIO::HideProgressInfo);
    m_job->setUiDelegate(nullptr);
    connect(m_job, &KIO::ListJob::entries, this, [this](KIO::Job *, const KIO::UDSEntryList &list) {
        for (const KIO::UDSEntry &e : list) {
            const QString name = e.stringValue(KIO::UDSEntry::UDS_NAME);
            if (name == QLatin1String(".") || name == QLatin1String(".."))
                continue;
            QString target = e.stringValue(KIO::UDSEntry::UDS_URL);
            const QUrl url = target.isEmpty() ? QUrl(QStringLiteral("smb://") + name) : QUrl(target);
            m_found << entry(url, e.stringValue(KIO::UDSEntry::UDS_DISPLAY_NAME).isEmpty() ? name : e.stringValue(KIO::UDSEntry::UDS_DISPLAY_NAME));
        }
        Q_EMIT changed();
    });
    connect(m_job, &KJob::result, this, [this] {
        m_job.clear();
        Q_EMIT changed();
    });
    Q_EMIT changed();
}

void Network::save(const QString &url, const QString &name)
{
    QVariantList list = m_settings->shares();
    for (const QVariant &v : list)
        if (v.toMap().value(QStringLiteral("url")).toString() == url)
            return;
    QUrl u(url);
    u.setPassword(QString());   // passwords stay with the system's sign-in store, never here
    list << QVariantMap{{QStringLiteral("url"), u.toString()}, {QStringLiteral("name"), name}};
    m_settings->setShares(list);
    Q_EMIT changed();
}

void Network::forget(const QString &url)
{
    QVariantList list = m_settings->shares();
    for (int i = 0; i < list.size(); ++i) {
        if (list.at(i).toMap().value(QStringLiteral("url")).toString() == url) {
            list.removeAt(i);
            break;
        }
    }
    m_settings->setShares(list);
    Q_EMIT changed();
}

QString Network::normalize(const QString &text) const
{
    QString t = text.trimmed();
    t.replace(QLatin1Char('\\'), QLatin1Char('/'));
    if (t.startsWith(QLatin1String("//")))
        t = QStringLiteral("smb:") + t;
    if (!t.contains(QLatin1String("://")))
        t = QStringLiteral("sftp://") + t;
    const QUrl u(t);
    if (!u.isValid() || u.host().isEmpty())
        return {};
    if (u.scheme() != QLatin1String("smb") && u.scheme() != QLatin1String("sftp") && u.scheme() != QLatin1String("ftp")
        && u.scheme() != QLatin1String("webdav") && u.scheme() != QLatin1String("webdavs"))
        return {};
    return u.toString();
}
