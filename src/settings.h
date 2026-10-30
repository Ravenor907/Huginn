#pragma once

#include <QObject>
#include <QSettings>

// Everything the owner can change, saved as they change it.
class Settings : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString layout READ layout WRITE setLayout NOTIFY changed)
    Q_PROPERTY(QString uiSize READ uiSize WRITE setUiSize NOTIFY changed)
    Q_PROPERTY(QString accent READ accent WRITE setAccent NOTIFY changed)
    Q_PROPERTY(QString textSize READ textSize WRITE setTextSize NOTIFY changed)
    Q_PROPERTY(bool highContrast READ highContrast WRITE setHighContrast NOTIFY changed)
    Q_PROPERTY(bool colorBlind READ colorBlind WRITE setColorBlind NOTIFY changed)
    Q_PROPERTY(bool reduceMotion READ reduceMotion WRITE setReduceMotion NOTIFY changed)
    Q_PROPERTY(bool runInBackground READ runInBackground WRITE setRunInBackground NOTIFY changed)
    Q_PROPERTY(bool nearbySharing READ nearbySharing WRITE setNearbySharing NOTIFY changed)
    Q_PROPERTY(QString transferFolder READ transferFolder WRITE setTransferFolder NOTIFY changed)
    Q_PROPERTY(bool noiseRemoval READ noiseRemoval WRITE setNoiseRemoval NOTIFY changed)
    // Settings › Appearance (the desktop layout): theme, message density and size, and the panels.
    // Their own signal, so a resize or a theme change doesn't wake everything that follows changed().
    Q_PROPERTY(QString theme READ theme WRITE setTheme NOTIFY appearanceChanged)
    Q_PROPERTY(QString density READ density WRITE setDensity NOTIFY appearanceChanged)
    Q_PROPERTY(QString messageSize READ messageSize WRITE setMessageSize NOTIFY appearanceChanged)
    Q_PROPERTY(int railWidth READ railWidth WRITE setRailWidth NOTIFY appearanceChanged)
    Q_PROPERTY(int listWidth READ listWidth WRITE setListWidth NOTIFY appearanceChanged)
    Q_PROPERTY(int memberWidth READ memberWidth WRITE setMemberWidth NOTIFY appearanceChanged)
    Q_PROPERTY(bool showList READ showList WRITE setShowList NOTIFY appearanceChanged)
    Q_PROPERTY(bool showMembers READ showMembers WRITE setShowMembers NOTIFY appearanceChanged)
    // Settings › Calls › Push to talk: in a voice room the microphone only sends while the key is held.
    Q_PROPERTY(bool pushToTalk READ pushToTalk WRITE setPushToTalk NOTIFY changed)

public:
    explicit Settings(QObject *parent = nullptr);

    // "auto", "handheld" or "desktop" (see Main.qml: Automatic picks desktop for a wide window with no controller in use).
    QString layout() const
    {
#ifdef Q_OS_WIN
        const QString v = value("layout", "desktop").toString();   // a Windows PC starts on the desktop layout
#else
        const QString v = value("layout", "auto").toString();
#endif
        return v == QLatin1String("handheld") || v == QLatin1String("desktop") ? v : QStringLiteral("auto");   // 0.5's "tv" is gone
    }
    QString uiSize() const { return value("uiSize", "100").toString(); }
    QString accent() const { return value("accent", "gold").toString(); }
    QString textSize() const { return value("textSize", "100").toString(); }
    bool highContrast() const { return value("highContrast", false).toBool(); }
    bool colorBlind() const { return value("colorBlind", false).toBool(); }
    bool reduceMotion() const { return value("reduceMotion", false).toBool(); }
    bool runInBackground() const { return value("runInBackground", true).toBool(); }
    bool nearbySharing() const { return value("nearbySharing", true).toBool(); }
    QString transferFolder() const;
    // Settings › Calls › Noise removal (on unless switched off). Off: calls keep the echo canceller and level
    // control but leave background noise in, for a voice the noise suppressor turns down too far.
    bool noiseRemoval() const { return saved("noiseRemoval", true).toBool(); }
    // "dark", "black" (true black, for OLED screens) or "light".
    QString theme() const { return value("theme", "dark").toString(); }
    // "cozy" (avatars, room between messages) or "compact" (more messages on screen).
    QString density() const { return value("density", "cozy").toString(); }
    // Message text, on top of Size and Text size (85 to 150).
    QString messageSize() const { return value("messageSize", "100").toString(); }
    // Panel widths in Normal-size pixels (the screen scales them).
    int railWidth() const { return value("railWidth", 83).toInt(); }
    int listWidth() const { return value("listWidth", 270).toInt(); }
    int memberWidth() const { return value("memberWidth", 230).toInt(); }
    bool showList() const { return value("showList", true).toBool(); }
    bool showMembers() const { return value("showMembers", true).toBool(); }
    bool pushToTalk() const { return value("pushToTalk", false).toBool(); }
    // The same, read where no Settings object is at hand (the call's audio, src/call/audioio.cpp).
    static QVariant saved(const QString &key, const QVariant &fallback) { return QSettings(QStringLiteral("Huginn"), QStringLiteral("Huginn")).value(key, fallback); }

    void setLayout(const QString &v) { set("layout", v); }
    void setUiSize(const QString &v) { set("uiSize", v); }
    void setAccent(const QString &v) { set("accent", v); }
    void setTextSize(const QString &v) { set("textSize", v); }
    void setHighContrast(bool v) { set("highContrast", v); }
    void setColorBlind(bool v) { set("colorBlind", v); }
    void setReduceMotion(bool v) { set("reduceMotion", v); }
    void setRunInBackground(bool v) { set("runInBackground", v); }
    void setNearbySharing(bool v) { set("nearbySharing", v); }
    void setTransferFolder(const QString &v) { set("transferFolder", v); }
    void setNoiseRemoval(bool v) { set("noiseRemoval", v); }
    void setTheme(const QString &v) { setLook("theme", v); }
    void setDensity(const QString &v) { setLook("density", v); }
    void setMessageSize(const QString &v) { setLook("messageSize", v); }
    void setRailWidth(int v) { setLook("railWidth", v); }
    void setListWidth(int v) { setLook("listWidth", v); }
    void setMemberWidth(int v) { setLook("memberWidth", v); }
    void setShowList(bool v) { setLook("showList", v); }
    void setShowMembers(bool v) { setLook("showMembers", v); }
    void setPushToTalk(bool v) { set("pushToTalk", v); }

Q_SIGNALS:
    void changed();
    void appearanceChanged();

private:
    QVariant value(const QString &key, const QVariant &fallback) const { return m_settings.value(key, fallback); }
    void set(const QString &key, const QVariant &v);
    void setLook(const QString &key, const QVariant &v);
    QSettings m_settings;
};
