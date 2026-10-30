#pragma once

#include <QObject>
#include <QUrl>
#include <QVariantList>

class QQuickWindow;
class Settings;

// Small helpers QML needs: the Steam keyboard, Game Mode detection, the layout density, files
// to share, and the screenshot tour that CI runs.
class System : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool gameMode READ gameMode CONSTANT)
    // Desktop density (smaller, for a mouse and keyboard). Main.qml decides from Settings.layout.
    Q_PROPERTY(bool desktop READ desktop WRITE setDesktop NOTIFY layoutChanged)
    Q_PROPERTY(double uiScale READ uiScale NOTIFY layoutChanged)
    Q_PROPERTY(bool windowed READ windowed CONSTANT)
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(QString tourDir READ tourDir CONSTANT)

public:
    System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent = nullptr);

    static QString formatSize(qint64 bytes);
    static QString displayPath(const QUrl &url);
    static bool inFlatpak();

    bool gameMode() const { return m_gameMode; }
    bool desktop() const { return m_desktop; }
    void setDesktop(bool on);
    double uiScale() const;
    bool windowed() const { return m_windowed; }
    QString version() const;
    QString tourDir() const { return m_tourDir; }

    Q_INVOKABLE void showSteamKeyboard();
    Q_INVOKABLE void hideSteamKeyboard();
    // The newest files in Downloads, Documents, Pictures, Videos and Music, for sending:
    // [{ header, items: [{ name, detail, url, icon }] }], ready for the list picker.
    Q_INVOKABLE QVariantList sendableFiles(int perFolder) const;
    // The computer's own file picker, several at once. Empty when nothing was picked.
    Q_INVOKABLE QStringList pickFiles() const;
    // Ask the system not to dim or sleep while files are being sent or received.
    void setKeepAwake(bool on, const QString &reason);
    Q_INVOKABLE QString sizeText(double bytes) const { return formatSize(qint64(bytes)); }
    Q_INVOKABLE void grab(QQuickWindow *window, const QString &name);
    Q_INVOKABLE void tourFinished();

    static int s_qmlWarnings;

Q_SIGNALS:
    void layoutChanged();

private:
    QString m_inhibitHandle;     // portal request path
    uint m_screensaverCookie = 0;
    bool m_awake = false;
    void openSteamUrl(const QString &url);
    Settings *m_settings;
    QString m_tourDir;
    bool m_windowed;
    bool m_gameMode;
    bool m_desktop = false;
};
