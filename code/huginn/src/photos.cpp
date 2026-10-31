#include "photos.h"

#include <QBuffer>
#include <QImage>
#include <QImageReader>
#include <cstdio>

namespace Photos
{
namespace
{
QByteArray encode(QImage img, int limit)
{
    // Best quality that fits the size limit; shrinks further if even low quality doesn't fit.
    while (true) {
        for (int q : {85, 75, 65, 55, 45, 35}) {
            QByteArray out;
            QBuffer buf(&out);
            buf.open(QIODevice::WriteOnly);
            img.save(&buf, "JPEG", q);
            if (out.size() <= limit)
                return out;
        }
        img = img.scaled(qMax(1, int(img.width() * 0.75)), qMax(1, int(img.height() * 0.75)), Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }
}
}

QByteArray prepare(const QString &path, bool hd)
{
    QImageReader r(path);
    r.setAutoTransform(true);   // phones and cameras store some photos sideways; turn them upright
    QImage img = r.read();
    if (img.isNull())
        return {};
    const int side = hd ? 2048 : 640;
    if (qMax(img.width(), img.height()) > side)
        img = img.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);   // no transparency in a JPEG
    return scrub(encode(img, hd ? 2 * 1024 * 1024 : 60 * 1024));
}

QByteArray prepareImage(const QImage &in, int side, int limit)
{
    if (in.isNull())
        return {};
    QImage img = in;
    if (qMax(img.width(), img.height()) > side)
        img = img.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);   // no transparency in a JPEG
    return scrub(encode(img, limit));
}

QByteArray scrub(const QByteArray &b)
{
    // Removes EXIF (location, camera, time), XMP, ICC, maker notes (APP1 to APP15) and comments,
    // without touching the picture. Mirrors Photos.scrub in Muninn.
    if (b.size() < 4 || uchar(b[0]) != 0xFF || uchar(b[1]) != 0xD8)
        return {};
    QByteArray out;
    out.reserve(b.size());
    out.append(char(0xFF)).append(char(0xD8));
    int i = 2;
    while (i + 4 <= b.size()) {
        if (uchar(b[i]) != 0xFF)
            return {};
        const int marker = uchar(b[i + 1]);
        if (marker == 0xFF) { ++i; continue; }
        if (marker == 0xDA) { out.append(b.mid(i)); return out; }   // the picture itself, to the end
        if (marker == 0xD9) { out.append(char(0xFF)).append(char(0xD9)); return out; }
        const int len = (uchar(b[i + 2]) << 8) | uchar(b[i + 3]);
        if (len < 2 || i + 2 + len > b.size())
            return {};
        const bool metadata = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
        if (!metadata)
            out.append(b.mid(i, 2 + len));
        i += 2 + len;
    }
    return {};
}

bool isAdts(const QByteArray &b) { return b.size() >= 7 && b.size() <= 2 * 1024 * 1024 && uchar(b[0]) == 0xFF && (uchar(b[1]) & 0xF0) == 0xF0; }

QList<QByteArray> split(const QByteArray &data)
{
    QList<QByteArray> out;
    for (int i = 0; i < data.size(); i += kPiece)
        out << data.mid(i, kPiece);
    return out;
}

QByteArray join(const QList<QByteArray> &parts)
{
    QByteArray b;
    for (const QByteArray &p : parts)
        b.append(p);
    return b.size() >= 3 && b.size() <= kMaxPieces * kPiece ? scrub(b) : QByteArray();
}

QByteArray joinVoice(const QList<QByteArray> &parts)
{
    QByteArray b;
    for (const QByteArray &p : parts)
        b.append(p);
    return isAdts(b) ? b : QByteArray();
}

bool selfTest()
{
    auto seg = [](int m, const QByteArray &payload) {
        QByteArray s;
        s.append(char(0xFF)).append(char(m)).append(char((payload.size() + 2) >> 8)).append(char((payload.size() + 2) & 0xFF)).append(payload);
        return s;
    };
    QByteArray jpeg;
    jpeg.append(char(0xFF)).append(char(0xD8));
    jpeg += seg(0xE0, "JFIF") + seg(0xE1, "Exif GPS 37.7N") + seg(0xFE, "my comment") + seg(0xDB, QByteArray(65, 1));
    jpeg.append(char(0xFF)).append(char(0xDA)).append(char(0)).append(char(2)).append(QByteArray(50000, 0x11)).append(char(0xFF)).append(char(0xD9));
    const QByteArray clean = scrub(jpeg);
    const bool scrubbed = !clean.isEmpty() && !clean.contains("GPS") && !clean.contains("my comment") && clean.contains("JFIF");
    std::printf("photo metadata removed: %s\n", scrubbed ? "ok" : "FAILED");
    const bool round = join(split(clean)) == clean && split(clean).size() == 4;
    std::printf("photo pieces round trip: %s\n", round ? "ok" : "FAILED");
    const bool notJpeg = join({QByteArray("\x01\x02\x03\x04", 4)}).isEmpty();
    std::printf("non-JPEG rejected: %s\n", notJpeg ? "ok" : "FAILED");
    return scrubbed && round && notJpeg;
}
}
