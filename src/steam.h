#pragma once

#include <QAbstractListModel>
#include <QFutureWatcher>
#include <QString>
#include <QStringList>
#include <QVariantMap>

// Where Steam keeps things, read straight from its own files.
namespace SteamFiles
{
QString root();                                   // ~/.local/share/Steam (or ~/.steam/steam)
QStringList libraries();                          // every library folder, including SD cards
QVariantMap parseText(const QByteArray &vdf);     // libraryfolders.vdf, appmanifest_*.acf
QList<QVariantMap> parseShortcuts(const QByteArray &bin); // userdata/*/config/shortcuts.vdf
QString screenshotsDir();
}

// The Games screen: one row per installed game, Steam or non-Steam, by name.
class GamesModel : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(int count READ count NOTIFY countChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged)
    Q_PROPERTY(bool sortBySize READ sortBySize WRITE setSortBySize NOTIFY sortChanged)
    Q_PROPERTY(QString summary READ summary NOTIFY countChanged)

public:
    struct Game {
        QString appId;
        QString name;
        QString library;
        QString installDir;    // steamapps/common/<dir>, empty for non-Steam
        QString compat;        // steamapps/compatdata/<id>
        QString shader;        // steamapps/shadercache/<id>
        QString art;
        QString proton;
        qint64 gameBytes = -1;
        qint64 prefixBytes = -1;
        qint64 savesBytes = -1;
        qint64 shaderBytes = -1;
        qint64 total() const { return qMax<qint64>(0, gameBytes) + qMax<qint64>(0, prefixBytes) + qMax<qint64>(0, shaderBytes); }
    };
    enum Roles { NameRole = Qt::UserRole + 1, AppIdRole, SizeRole, InitialsRole, ArtRole };

    explicit GamesModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    int count() const { return int(m_games.size()); }
    bool loading() const { return m_loading; }
    bool sortBySize() const { return m_sortBySize; }
    void setSortBySize(bool v);
    QString summary() const;
    QStringList protectedRoots() const;

    Q_INVOKABLE void refresh();
    Q_INVOKABLE QVariantMap details(int row) const;
    Q_INVOKABLE void backupSaves(int row);
    Q_INVOKABLE void clearShaderCache(int row);
    Q_INVOKABLE void resetPrefix(int row);

    static QString initials(const QString &name);
    static QList<Game> scan();

Q_SIGNALS:
    void countChanged();
    void loadingChanged();
    void sortChanged();
    void finished(bool ok, const QString &message);

private:
    void sort();
    QString backupTo(const Game &g, QString *error) const;

    QList<Game> m_games;
    QFutureWatcher<QList<Game>> m_watcher;
    bool m_loading = false;
    bool m_sortBySize = true;
};
