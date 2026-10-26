#pragma once

#include <QObject>
#include <QSettings>
#include <QStringList>
#include <QVariantList>

// Everything the owner can change, saved as they change it.
class Settings : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString layout READ layout WRITE setLayout NOTIFY changed)
    Q_PROPERTY(QString accent READ accent WRITE setAccent NOTIFY changed)
    Q_PROPERTY(QString channel READ channel WRITE setChannel NOTIFY changed)
    Q_PROPERTY(bool showHidden READ showHidden WRITE setShowHidden NOTIFY changed)
    Q_PROPERTY(bool runInBackground READ runInBackground WRITE setRunInBackground NOTIFY changed)
    Q_PROPERTY(bool protectLibrary READ protectLibrary WRITE setProtectLibrary NOTIFY changed)
    Q_PROPERTY(bool confirmDelete READ confirmDelete WRITE setConfirmDelete NOTIFY changed)
    Q_PROPERTY(bool ventoySecureBoot READ ventoySecureBoot WRITE setVentoySecureBoot NOTIFY changed)
    Q_PROPERTY(QString transferFolder READ transferFolder WRITE setTransferFolder NOTIFY changed)

public:
    explicit Settings(QObject *parent = nullptr);

    QString layout() const { return value("layout", "auto").toString(); }
    QString accent() const { return value("accent", "gold").toString(); }
    QString channel() const { return value("channel", "stable").toString(); }
    bool showHidden() const { return value("showHidden", false).toBool(); }
    bool runInBackground() const { return value("runInBackground", true).toBool(); }
    bool protectLibrary() const { return value("protectLibrary", true).toBool(); }
    bool confirmDelete() const { return value("confirmDelete", false).toBool(); }
    bool ventoySecureBoot() const { return value("ventoySecureBoot", true).toBool(); }
    QString transferFolder() const;

    void setLayout(const QString &v) { set("layout", v); }
    void setAccent(const QString &v) { set("accent", v); }
    void setChannel(const QString &v) { set("channel", v); }
    void setShowHidden(bool v) { set("showHidden", v); }
    void setRunInBackground(bool v) { set("runInBackground", v); }
    void setProtectLibrary(bool v) { set("protectLibrary", v); }
    void setConfirmDelete(bool v) { set("confirmDelete", v); }
    void setVentoySecureBoot(bool v) { set("ventoySecureBoot", v); }
    void setTransferFolder(const QString &v) { set("transferFolder", v); }

    QVariantList shares() const;
    void setShares(const QVariantList &list);

Q_SIGNALS:
    void changed();

private:
    QVariant value(const QString &key, const QVariant &fallback) const { return m_settings.value(key, fallback); }
    void set(const QString &key, const QVariant &v);
    QSettings m_settings;
};
