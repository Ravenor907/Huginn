#pragma once

#include <QAbstractListModel>
#include <QFutureWatcher>
#include <QSet>
#include <QUrl>
#include <memory>

class KArchive;
class KArchiveDirectory;

// Looks inside zip, 7z and tar archives with KArchive, and extracts all or part of one.
class ArchiveModel : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(QUrl url READ url NOTIFY openedChanged)
    Q_PROPERTY(QString fileName READ fileName NOTIFY openedChanged)
    Q_PROPERTY(QString baseName READ baseName NOTIFY openedChanged)
    Q_PROPERTY(QString details READ details NOTIFY openedChanged)
    Q_PROPERTY(QString summary READ summary NOTIFY pathChanged)
    Q_PROPERTY(QString title READ title NOTIFY pathChanged)
    Q_PROPERTY(bool atRoot READ atRoot NOTIFY pathChanged)
    Q_PROPERTY(bool loading READ loading CONSTANT)
    Q_PROPERTY(QString error READ error NOTIFY openedChanged)
    Q_PROPERTY(int count READ count NOTIFY pathChanged)
    Q_PROPERTY(int selectedCount READ selectedCount NOTIFY selectionChanged)
    Q_PROPERTY(QString selectedSize READ selectedSize NOTIFY selectionChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)

public:
    enum Roles { NameRole = Qt::UserRole + 1, IsDirRole, MetaRole, KindRole, SelectedRole, HiddenRole };
    explicit ArchiveModel(QObject *parent = nullptr);
    ~ArchiveModel() override;

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    QUrl url() const { return m_url; }
    QString fileName() const { return m_url.fileName(); }
    QString baseName() const;
    QString details() const { return m_summary; }
    QString summary() const;
    QString title() const;
    bool atRoot() const { return m_path.isEmpty(); }
    bool loading() const { return false; }
    QString error() const { return m_error; }
    int count() const { return int(m_rows.size()); }
    int selectedCount() const { return int(m_selected.size()); }
    QString selectedSize() const;
    bool busy() const { return m_watcher.isRunning(); }

    Q_INVOKABLE void open(const QUrl &url);
    Q_INVOKABLE void openSample();
    Q_INVOKABLE void enter(int row);
    Q_INVOKABLE void up();
    Q_INVOKABLE void toggleSelected(int row);
    Q_INVOKABLE bool isDirAt(int row) const;
    Q_INVOKABLE void extract(const QUrl &destDir, const QString &subfolder);

    static std::unique_ptr<KArchive> openArchive(const QString &path);

Q_SIGNALS:
    void openedChanged();
    void pathChanged();
    void selectionChanged();
    void busyChanged();
    void finished(bool ok, const QString &message);
    void restoreIndex(int index);

private:
    struct Row { QString name; bool isDir; qint64 size; };
    void load();

    QUrl m_url;
    std::unique_ptr<KArchive> m_archive;
    QString m_path;                 // inside the archive, "" is the top
    QList<Row> m_rows;
    QSet<QString> m_selected;       // full paths inside the archive
    QHash<QString, qint64> m_sizes;
    QString m_summary;
    QString m_error;
    QFutureWatcher<QString> m_watcher;
};
