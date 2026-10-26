#pragma once

#include <QObject>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>

class QQuickWindow;
class Settings;

// Small helpers QML needs: the Steam keyboard, Game Mode and TV detection, file details,
// text previews, and the screenshot tour that CI runs.
class System : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool gameMode READ gameMode CONSTANT)
    Q_PROPERTY(bool tvLayout READ tvLayout NOTIFY layoutChanged)
    Q_PROPERTY(double uiScale READ uiScale NOTIFY layoutChanged)
    Q_PROPERTY(bool windowed READ windowed CONSTANT)
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(QString tourDir READ tourDir CONSTANT)

public:
    System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent = nullptr);

    static QString formatSize(qint64 bytes);
    static QString displayPath(const QUrl &url);
    static bool inFlatpak();
    static int runHost(const QStringList &args, QByteArray *out = nullptr, const QByteArray &input = {}, int timeoutMs = 30000);

    bool gameMode() const { return m_gameMode; }
    bool tvLayout() const;
    double uiScale() const;
    bool windowed() const { return m_windowed; }
    QString version() const;
    QString tourDir() const { return m_tourDir; }

    Q_INVOKABLE void showSteamKeyboard();
    Q_INVOKABLE void hideSteamKeyboard();
    Q_INVOKABLE QString kindOf(const QUrl &url) const;
    Q_INVOKABLE QVariantMap fileInfo(const QUrl &url) const;
    Q_INVOKABLE QString readText(const QUrl &url, int maxBytes) const;
    Q_INVOKABLE QVariantMap readLines(const QUrl &url) const;
    Q_INVOKABLE QString writeLines(const QUrl &url, const QStringList &lines) const;
    Q_INVOKABLE QVariantList imagesBeside(const QUrl &url) const;
    Q_INVOKABLE QVariantList sampleImages();
    Q_INVOKABLE QString sampleText();
    Q_INVOKABLE QVariantList newestDownloads(int limit) const;
    Q_INVOKABLE QVariantList driveSummary() const;
    Q_INVOKABLE QUrl homeUrl() const;
    Q_INVOKABLE QUrl downloadsUrl() const;
    Q_INVOKABLE QString sizeText(double bytes) const { return formatSize(qint64(bytes)); }
    Q_INVOKABLE QUrl parentUrl(const QUrl &url) const;
    Q_INVOKABLE QString parentPath(const QUrl &url) const;
    Q_INVOKABLE QString fileName(const QVariant &url) const;
    Q_INVOKABLE QString prettyPath(const QVariant &url) const { return displayPath(QUrl(url.toString())); }
    Q_INVOKABLE void grab(QQuickWindow *window, const QString &name);
    Q_INVOKABLE void tourFinished();

    static int s_qmlWarnings;

Q_SIGNALS:
    void layoutChanged();

private:
    void openSteamUrl(const QString &url);
    Settings *m_settings;
    QString m_tourDir;
    bool m_windowed;
    bool m_gameMode;
};
