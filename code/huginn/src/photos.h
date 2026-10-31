#pragma once

#include <QByteArray>
#include <QList>
#include <QString>

class QImage;

// Photos and voice messages for chat, the same as Muninn's Photos.kt and Voice.kt so the two agree.
//  - Photos are re-encoded (only pixels survive) and then scrubbed of every metadata block, so
//    location, camera and dates never leave this computer. Normal: 640 px, at most 60 KB.
//    High quality: 2048 px, at most 2 MB.
//  - Voice messages are bare AAC frames (ADTS): no container, so nothing but the sound.
//  - Both travel in 16 KB pieces, which fit every route (Bluetooth, Wi-Fi, relays).
namespace Photos
{
const int kPiece = 16 * 1024;
const int kMaxPieces = 200;            // 3.2 MB
const int kInternetPieces = 130;       // 2 MB over the relays at most
const int kMaxVoiceMs = 5 * 60 * 1000;

QByteArray prepare(const QString &path, bool hd);   // empty if it isn't a picture
QByteArray scrub(const QByteArray &jpeg);           // empty if it isn't a well-formed JPEG
// A picture already in memory (a Labs report's screenshot): at most side px and limit bytes, scrubbed the same way.
QByteArray prepareImage(const QImage &img, int side, int limit);
bool isAdts(const QByteArray &b);
QList<QByteArray> split(const QByteArray &data);
QByteArray join(const QList<QByteArray> &parts);    // a JPEG, scrubbed; empty otherwise
QByteArray joinVoice(const QList<QByteArray> &parts);
bool selfTest();
}
