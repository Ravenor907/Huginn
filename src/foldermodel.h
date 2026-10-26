#pragma once

#include <QAbstractListModel>
#include <QPointer>
#include <QUrl>
#include <QVariantList>

#include <KIO/UDSEntry>

class KJob;
namespace KIO { class ListJob; class Job; }

// One folder's contents, read through KIO so local folders, the trash, network shares and
// more all look the same. Folders first, then files, sorted the way people sort.
class FolderModel : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(QUrl url READ url NOTIFY urlChanged)
    Q_PROPERTY(QString title READ title NOTIFY urlChanged)
    Q_PROPERTY(int count READ count NOTIFY countChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged)
    Q_PROPERTY(QString error READ error NOTIFY loadingChanged)
    Q_PROPERTY(QString summary READ summary NOTIFY countChanged)
    Q_PROPERTY(bool showHidden READ showHidden WRITE setShowHidden NOTIFY showHiddenChanged)
    Q_PROPERTY(QString filter READ filter WRITE setFilter NOTIFY filterChanged)
    Q_PROPERTY(int selectedCount READ selectedCount NOTIFY selectionChanged)
    Q_PROPERTY(QString selectedSize READ selectedSize NOTIFY selectionChanged)

public:
    enum Roles { NameRole = Qt::UserRole + 1, UrlRole, IsDirRole, MetaRole, KindRole, SelectedRole, HiddenRole };

    explicit FolderModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    QUrl url() const { return m_url; }
    QString title() const;
    int count() const { return int(m_visible.size()); }
    bool loading() const { return m_job != nullptr; }
    QString error() const { return m_error; }
    QString summary() const;
    bool showHidden() const { return m_showHidden; }
    void setShowHidden(bool v);
    QString filter() const { return m_filter; }
    void setFilter(const QString &f);
    int selectedCount() const;
    QString selectedSize() const;

    Q_INVOKABLE void setUrl(const QUrl &url);
    Q_INVOKABLE void up();
    Q_INVOKABLE void refresh();
    Q_INVOKABLE void toggleSelected(int row);
    Q_INVOKABLE void clearSelection();
    Q_INVOKABLE QVariantList selectedUrls() const;
    Q_INVOKABLE QStringList selectedNames() const;
    Q_INVOKABLE QUrl urlAt(int row) const;
    Q_INVOKABLE QString nameAt(int row) const;
    Q_INVOKABLE QString kindAt(int row) const;
    Q_INVOKABLE bool isDirAt(int row) const;
    Q_INVOKABLE QString fileName(const QUrl &url) const { return url.fileName(); }

    static QString kindFor(const QString &name, const QString &mime, bool isDir);

Q_SIGNALS:
    void urlChanged();
    void countChanged();
    void loadingChanged();
    void showHiddenChanged();
    void filterChanged();
    void selectionChanged();
    void restoreIndex(int index);

private:
    struct Item {
        QString name;
        QUrl url;
        bool isDir = false;
        bool hidden = false;
        qint64 size = -1;
        qint64 mtime = 0;
        QString mime;
        QString kind;
        bool selected = false;
    };

    void onEntries(KIO::Job *job, const KIO::UDSEntryList &list);
    void onResult(KJob *job);
    void rebuild(const QString &keepName = {});

    QUrl m_url;
    QString m_cameFrom;
    QPointer<KIO::ListJob> m_job;
    QList<Item> m_all;
    QList<Item> m_incoming;
    QList<int> m_visible;
    QString m_error;
    QString m_filter;
    bool m_showHidden = false;
};
