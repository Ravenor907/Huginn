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
    Q_PROPERTY(QString uiSize READ uiSize WRITE setUiSize NOTIFY changed)
    Q_PROPERTY(QString accent READ accent WRITE setAccent NOTIFY changed)
    Q_PROPERTY(bool showHidden READ showHidden WRITE setShowHidden NOTIFY changed)
    Q_PROPERTY(bool runInBackground READ runInBackground WRITE setRunInBackground NOTIFY changed)
    Q_PROPERTY(bool protectLibrary READ protectLibrary WRITE setProtectLibrary NOTIFY changed)
    Q_PROPERTY(bool confirmDelete READ confirmDelete WRITE setConfirmDelete NOTIFY changed)
    Q_PROPERTY(bool ventoySecureBoot READ ventoySecureBoot WRITE setVentoySecureBoot NOTIFY changed)
    Q_PROPERTY(bool nearbySharing READ nearbySharing WRITE setNearbySharing NOTIFY changed)
    Q_PROPERTY(QString transferFolder READ transferFolder WRITE setTransferFolder NOTIFY changed)
    Q_PROPERTY(bool showSize READ showSize WRITE setShowSize NOTIFY changed)
    Q_PROPERTY(bool showModified READ showModified WRITE setShowModified NOTIFY changed)
    Q_PROPERTY(bool showCount READ showCount WRITE setShowCount NOTIFY changed)
    Q_PROPERTY(bool showType READ showType WRITE setShowType NOTIFY changed)
    Q_PROPERTY(QString sortMode READ sortMode WRITE setSortMode NOTIFY changed)
    Q_PROPERTY(QStringList recentDestinations READ recentDestinations NOTIFY changed)
    Q_PROPERTY(QStringList recentFolders READ recentFolders NOTIFY recentFoldersChanged)
    Q_PROPERTY(QVariantList recentFiles READ recentFiles NOTIFY recentFilesChanged)

public:
    explicit Settings(QObject *parent = nullptr);

    QString layout() const { return value("layout", "auto").toString(); }
    QString uiSize() const { return value("uiSize", "100").toString(); }
    QString accent() const { return value("accent", "gold").toString(); }
    bool showHidden() const { return value("showHidden", false).toBool(); }
    bool runInBackground() const { return value("runInBackground", true).toBool(); }
    bool protectLibrary() const { return value("protectLibrary", true).toBool(); }
    bool confirmDelete() const { return value("confirmDelete", false).toBool(); }
    bool ventoySecureBoot() const { return value("ventoySecureBoot", true).toBool(); }
    bool nearbySharing() const { return value("nearbySharing", true).toBool(); }
    QString transferFolder() const;
    bool showSize() const { return value("showSize", true).toBool(); }
    bool showModified() const { return value("showModified", false).toBool(); }
    bool showCount() const { return value("showCount", true).toBool(); }
    bool showType() const { return value("showType", false).toBool(); }
    QString sortMode() const { return value("sortMode", "name").toString(); }
    QStringList recentDestinations() const { return value("recentDestinations", QStringList()).toStringList(); }
    Q_INVOKABLE void rememberDestination(const QString &url);
    QStringList recentFolders() const { return value("recentFolders", QStringList()).toStringList(); }
    void rememberFolder(const QString &url);
    // Files opened, copied or moved lately, newest first: { url, name, folder, when }. Gone files drop out.
    QVariantList recentFiles() const;
    Q_INVOKABLE void rememberFile(const QString &url);

    void setLayout(const QString &v) { set("layout", v); }
    void setUiSize(const QString &v) { set("uiSize", v); }
    void setAccent(const QString &v) { set("accent", v); }
    void setShowHidden(bool v) { set("showHidden", v); }
    void setRunInBackground(bool v) { set("runInBackground", v); }
    void setProtectLibrary(bool v) { set("protectLibrary", v); }
    void setConfirmDelete(bool v) { set("confirmDelete", v); }
    void setVentoySecureBoot(bool v) { set("ventoySecureBoot", v); }
    void setNearbySharing(bool v) { set("nearbySharing", v); }
    void setTransferFolder(const QString &v) { set("transferFolder", v); }
    void setShowSize(bool v) { set("showSize", v); }
    void setShowModified(bool v) { set("showModified", v); }
    void setShowCount(bool v) { set("showCount", v); }
    void setShowType(bool v) { set("showType", v); }
    void setSortMode(const QString &v) { set("sortMode", v); }

    QVariantList shares() const;
    void setShares(const QVariantList &list);

Q_SIGNALS:
    void changed();
    void recentFoldersChanged();
    void recentFilesChanged();

private:
    QVariant value(const QString &key, const QVariant &fallback) const { return m_settings.value(key, fallback); }
    void set(const QString &key, const QVariant &v);
    QSettings m_settings;
};
