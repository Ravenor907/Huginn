#pragma once

#include <QCache>
#include <QImage>
#include <QMutex>
#include <QQuickImageProvider>

class Chat;

// Small pictures for album grids and the Shared page: "image://thumb/<photo id>". Each photo is read from the
// vault once, shrunk and kept in memory (never on disk), so a long Shared page never decodes every photo at
// full size. The vault is only read on the main thread; shrinking happens on Qt's loading thread.
class ThumbProvider : public QQuickImageProvider
{
public:
    explicit ThumbProvider(Chat *chat);
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

private:
    Chat *m_chat;
    QMutex m_lock;
    QCache<QString, QImage> m_cache;
};
