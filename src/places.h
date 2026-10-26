#pragma once

#include <QObject>
#include <QTimer>
#include <QUrl>
#include <QVariantList>

// The sidebar: home, SD cards, Steam, games, screenshots, emulation, downloads, trash and
// the tool screens. Watches for cards and drives coming and going.
class Places : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList list READ list NOTIFY listChanged)

public:
    explicit Places(QObject *parent = nullptr);
    QVariantList list() const { return m_list; }
    Q_INVOKABLE QUrl startUrl(int pane) const;
    static QStringList cardMounts();

Q_SIGNALS:
    void listChanged();

private:
    void rebuild();
    QVariantList m_list;
    QStringList m_lastMounts;
    QTimer m_watch;
};
