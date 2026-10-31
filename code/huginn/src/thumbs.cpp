#include "thumbs.h"
#include "chat.h"

#include <QCoreApplication>
#include <QMetaObject>
#include <QMutexLocker>
#include <QThread>

namespace
{
const int kSide = 320;
}

ThumbProvider::ThumbProvider(Chat *chat)
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_chat(chat)
{
    m_cache.setMaxCost(48 * 1024 * 1024);   // bytes of pictures kept, about 120 thumbnails
}

QImage ThumbProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    Q_UNUSED(requestedSize)
    {
        QMutexLocker l(&m_lock);
        if (const QImage *hit = m_cache.object(id)) {
            if (size)
                *size = hit->size();
            return *hit;
        }
    }
    QByteArray bytes;
    if (QThread::currentThread() == QCoreApplication::instance()->thread())
        bytes = m_chat->blob(id);
    else
        QMetaObject::invokeMethod(m_chat, [this, id, &bytes] { bytes = m_chat->blob(id); }, Qt::BlockingQueuedConnection);
    QImage img = QImage::fromData(bytes, "JPEG");
    bytes.fill(0);
    if (img.isNull())
        return {};
    if (qMax(img.width(), img.height()) > kSide)
        img = img.scaled(kSide, kSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    if (size)
        *size = img.size();
    QMutexLocker l(&m_lock);
    m_cache.insert(id, new QImage(img), int(img.sizeInBytes()));
    return img;
}
