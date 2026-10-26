#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QJsonValue>
#include <QString>
#include <QVariantMap>

// The deterministic parts of 0.6 group chats (docs/protocol-0.6.md, sections 2 and 3), kept apart
// from Chat so the self-test can check them against tests/proto06-vectors.json byte for byte:
//  - canonical JSON (what gets signed and hashed), never QJsonDocument's own text
//  - signed objects: ECDSA P-256 / SHA-256 with the chat key, DER, standard base64
//  - group state: well-formed, accepted against the current state, the conflict rule
//  - per-member envelope ids, ids and tokens, group codes (MUNINNG1:)
// tools/proto06_ref.py is the reference; when this and it disagree, it wins.
namespace GroupWire
{
extern const char kDomain[];          // "huginn-muninn group v1"
extern const char kCodePrefix[];      // "MUNINNG1:"
const int kMaxMembers = 50;
const int kMaxChannels = 20;
const int kMaxName = 40;
const int kMaxChannelName = 32;
const int kMaxStateBytes = 24 * 1024;
const int kHistory = 32;              // accepted states kept for sync
const int kStatesAnswer = 16;         // at most this many in one "states" answer
const int kMaxHeld = 50;              // messages from unknown senders held per group
const int kMaxReceiptIds = 40;
const qint64 kTokenLife = 3600000;    // group codes and invites: one hour

// Canonical JSON (2.1). ok is false when the value can't be canonical (null, a fraction, a
// negative or too big number, a control character, a non-ASCII key).
QByteArray canon(const QJsonValue &v, bool *ok);
// UTF-8(domain + "\n" + canon(obj without "sig")); empty when obj isn't canonical.
QByteArray signedBytes(const QString &domain, const QJsonObject &obj);
// Lowercase hex SHA-256 of canon(obj with "sig"); empty when obj isn't canonical.
QString hash(const QJsonObject &obj);
// obj with "sig" set (random k); obj unchanged and ok false if signing fails.
QJsonObject sign(const QByteArray &privateDer, const QString &domain, const QJsonObject &obj, bool *ok = nullptr);
bool verify(const QString &publicKeyB64, const QString &domain, const QJsonObject &obj);

// Code points, as the limits count them.
int length(const QString &s);
// Empty when well-formed (3.2), otherwise the reason. Does not check the signature.
QString checkState(const QJsonObject &s);
// 3.3 without step 2's extras (those need the app's own key and invite): cur null is first sight.
struct Verdict {
    bool ok = false;
    QString why;
};
Verdict accept(const QJsonObject *cur, const QJsonObject &next);
// The conflict rule on its own: true when a wins over b.
bool wins(const QJsonObject &a, const QJsonObject &b);

// 2.3
QString envelopeId(const QString &logicalId, const QString &recipientKey);
QString newGroupId();       // 16 random bytes, base64url (22 chars)
QString newChannelId();     // 8 random bytes, base64url (11 chars)
QString newLogicalId();     // 16 random bytes, 32 lowercase hex
QString newToken();         // 16 random bytes, base64url
bool isGroupId(const QString &g);
bool isChannelId(const QString &c);
bool isLogicalId(const QString &id);
bool isMailbox(const QString &p);

// Small helpers for building states (members and admins sorted, as checkState wants).
QString cleanName(const QString &name, int max);   // no control characters, at most max characters
QJsonObject member(const QString &key, const QString &name, const QString &mailbox);
QJsonObject withMembers(QJsonObject s, QList<QJsonObject> members);
QJsonObject withAdmins(QJsonObject s, QStringList admins);
QStringList keysOf(const QJsonObject &s);           // member keys
QStringList adminsOf(const QJsonObject &s);
bool isMember(const QJsonObject &s, const QString &key);
bool isAdmin(const QJsonObject &s, const QString &key);
QJsonObject memberEntry(const QJsonObject &s, const QString &key);
QJsonObject channel(const QJsonObject &s, const QString &id);

// Group codes (3.5): MUNINNG1:<base64url(JSON {g, gn, a, an, p, i, x[, o]})>.
QString code(const QString &g, const QString &groupName, const QString &adminKey, const QString &adminName, const QString &mailbox,
             const QString &token, qint64 expiresMs, const QString &onion = QString());
// {g, name, admin, adminName, mailbox, token, expires (ms), expired, onion} or empty when it isn't one.
QVariantMap parseCode(const QString &text, qint64 nowMs);

// Checks tests/proto06-vectors.json (group section and canonical JSON, with chat-vectors.json next
// to it for the keys), plus state limits, signing and group codes. Prints what it found.
bool selfTest(const QString &vectorsPath);
}
