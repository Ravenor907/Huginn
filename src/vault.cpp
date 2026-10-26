#include "vault.h"

#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>
#include <cstdio>

#include <openssl/evp.h>
#include <openssl/rand.h>

namespace
{
const QByteArray kMagic = QByteArrayLiteral("MUNV1");
const int kRounds = 310000;

QString dataDir()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/chat");
    QDir().mkpath(dir);
    return dir;
}

QByteArray random(int n)
{
    QByteArray b(n, 0);
    RAND_bytes(reinterpret_cast<unsigned char *>(b.data()), n);
    return b;
}

QSettings &settings()
{
    static QSettings s(QStringLiteral("Huginn"), QStringLiteral("Huginn"));
    return s;
}
}

Vault::Vault(QObject *parent)
    : QObject(parent)
{
}

QString Vault::keyFile() const { return dataDir() + QStringLiteral("/vault.key"); }
QString Vault::path(const QString &name) const { return dataDir() + QLatin1Char('/') + name; }

bool Vault::exists() const { return QFile::exists(keyFile()); }

bool Vault::usesPattern() const
{
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    return QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("kind")).toString() == QLatin1String("pattern");
}

int Vault::waitSeconds() const
{
    const qint64 until = settings().value(QStringLiteral("vault/until"), 0).toLongLong();
    const qint64 left = until - QDateTime::currentMSecsSinceEpoch();
    return left > 0 ? int((left + 999) / 1000) : 0;
}

bool Vault::unlock(const QString &secret)
{
    if (waitSeconds() > 0)
        return false;
    QFile f(keyFile());
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    const QByteArray kek = derive(secret, QByteArray::fromBase64(o.value(QStringLiteral("salt")).toString().toLatin1()),
                                  o.value(QStringLiteral("rounds")).toInt(kRounds));
    bool ok = false;
    const QByteArray key = open(kek, QByteArray::fromBase64(o.value(QStringLiteral("wrapped")).toString().toLatin1()), &ok);
    if (!ok || key.size() != 32) {
        // Every 5 wrong tries, wait 30 seconds (kept across restarts), so guessing is slow.
        const int fails = settings().value(QStringLiteral("vault/fails"), 0).toInt() + 1;
        settings().setValue(QStringLiteral("vault/fails"), fails);
        if (fails % 5 == 0)
            settings().setValue(QStringLiteral("vault/until"), QDateTime::currentMSecsSinceEpoch() + 30000);
        Q_EMIT changed();
        return false;
    }
    settings().setValue(QStringLiteral("vault/fails"), 0);
    const bool first = m_key.isEmpty();
    m_key = key;
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

bool Vault::setSecret(const QString &secret, bool pattern)
{
    if (secret.size() < 4)
        return false;
    if (exists() && m_key.isEmpty())
        return false;   // changing needs the old lock opened first
    const bool first = m_key.isEmpty();
    if (first)
        m_key = random(32);
    const QByteArray salt = random(16);
    const QJsonObject o{{QStringLiteral("mode"), QStringLiteral("password")}, {QStringLiteral("kind"), pattern ? QStringLiteral("pattern") : QStringLiteral("text")},
                        {QStringLiteral("salt"), QString::fromLatin1(salt.toBase64())}, {QStringLiteral("rounds"), kRounds},
                        {QStringLiteral("wrapped"), QString::fromLatin1(seal(derive(secret, salt, kRounds), m_key).toBase64())}};
    QSaveFile f(keyFile());
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
    if (!f.commit())
        return false;
    m_screenLocked = false;
    Q_EMIT changed();
    if (first)
        Q_EMIT opened();
    return true;
}

void Vault::eraseAll()
{
    QDir(dataDir()).removeRecursively();
    m_key.fill(0);
    m_key.clear();
    settings().remove(QStringLiteral("vault"));
    Q_EMIT changed();
}

bool Vault::write(const QString &name, const QByteArray &data) const
{
    if (m_key.isEmpty())
        return false;
    QDir().mkpath(QFileInfo(path(name)).absolutePath());
    QSaveFile f(path(name));
    if (!f.open(QIODevice::WriteOnly))
        return false;
    f.write(kMagic + seal(m_key, data));
    return f.commit();
}

QByteArray Vault::read(const QString &name) const
{
    if (m_key.isEmpty())
        return {};
    QFile f(path(name));
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const QByteArray b = f.readAll();
    f.close();
    if (!b.startsWith(kMagic)) {
        write(name, b);   // from before encryption: save it encrypted now
        return b;
    }
    bool ok = false;
    const QByteArray plain = open(m_key, b.mid(kMagic.size()), &ok);
    return ok ? plain : QByteArray();
}

void Vault::remove(const QString &name) const { QFile::remove(path(name)); }

// ------------------------------------------------------------------ the math

QByteArray Vault::seal(const QByteArray &key, const QByteArray &plain)
{
    const QByteArray iv = random(12);
    QByteArray out(plain.size() + 16, 0);
    int len = 0, total = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_EncryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(plain.constData()), int(plain.size()));
    total = len;
    EVP_EncryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + total, &len);
    total += len;
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, out.data() + total);
    EVP_CIPHER_CTX_free(c);
    out.resize(total + 16);
    return iv + out;   // the same layout Java's AES/GCM gives: iv, ciphertext, tag
}

QByteArray Vault::open(const QByteArray &key, const QByteArray &data, bool *ok)
{
    *ok = false;
    if (key.size() != 32 || data.size() < 12 + 16)
        return {};
    const QByteArray iv = data.left(12), tag = data.right(16), ct = data.mid(12, data.size() - 28);
    QByteArray out(ct.size(), 0);
    int len = 0;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char *>(key.constData()), reinterpret_cast<const unsigned char *>(iv.constData()));
    EVP_DecryptUpdate(c, reinterpret_cast<unsigned char *>(out.data()), &len, reinterpret_cast<const unsigned char *>(ct.constData()), int(ct.size()));
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, const_cast<char *>(tag.constData()));
    const bool good = EVP_DecryptFinal_ex(c, reinterpret_cast<unsigned char *>(out.data()) + len, &len) > 0;
    EVP_CIPHER_CTX_free(c);
    *ok = good;
    return good ? out : QByteArray();
}

QByteArray Vault::derive(const QString &secret, const QByteArray &salt, int rounds)
{
    const QByteArray pw = secret.toUtf8();
    QByteArray out(32, 0);
    PKCS5_PBKDF2_HMAC(pw.constData(), int(pw.size()), reinterpret_cast<const unsigned char *>(salt.constData()), int(salt.size()), rounds, EVP_sha256(), 32,
                      reinterpret_cast<unsigned char *>(out.data()));
    return out;
}

bool Vault::selfTest()
{
    bool all = true;
    const QByteArray key(32, '\x07');
    bool ok = false;
    const QByteArray sealed = seal(key, "secret chats");
    const bool round = open(key, sealed, &ok) == "secret chats" && ok;
    std::printf("vault seal/open round trip: %s\n", round ? "ok" : "FAILED");
    all &= round;
    open(QByteArray(32, '\x08'), sealed, &ok);
    std::printf("vault wrong key rejected: %s\n", !ok ? "ok" : "FAILED");
    all &= !ok;
    // PBKDF2-HMAC-SHA256("password", "salt", 1 round) from RFC 7914 section 11.
    const bool kdf = derive(QStringLiteral("password"), "salt", 1).toHex() == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b";
    std::printf("vault PBKDF2 test vector: %s\n", kdf ? "ok" : "FAILED");
    all &= kdf;
    return all;
}
