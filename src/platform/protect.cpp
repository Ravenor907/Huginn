#include "protect.h"

#ifdef Q_OS_WIN
#include <windows.h>
#include <wincrypt.h>
#include <dpapi.h>

namespace
{
const QByteArray kEntropy = QByteArrayLiteral("Huginn vault");

DATA_BLOB blob(const QByteArray &b)
{
    DATA_BLOB d;
    d.cbData = DWORD(b.size());
    d.pbData = reinterpret_cast<BYTE *>(const_cast<char *>(b.constData()));
    return d;
}
}

bool Platform::protect(const QByteArray &data, QByteArray *out)
{
    DATA_BLOB in = blob(data), extra = blob(kEntropy), result{};
    if (!CryptProtectData(&in, L"Huginn", &extra, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &result))
        return false;
    *out = QByteArray(reinterpret_cast<const char *>(result.pbData), int(result.cbData));
    LocalFree(result.pbData);
    return true;
}

bool Platform::unprotect(const QByteArray &data, QByteArray *out)
{
    DATA_BLOB in = blob(data), extra = blob(kEntropy), result{};
    if (!CryptUnprotectData(&in, nullptr, &extra, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &result))
        return false;
    *out = QByteArray(reinterpret_cast<const char *>(result.pbData), int(result.cbData));
    SecureZeroMemory(result.pbData, result.cbData);
    LocalFree(result.pbData);
    return true;
}
#else
bool Platform::protect(const QByteArray &, QByteArray *) { return false; }
bool Platform::unprotect(const QByteArray &, QByteArray *) { return false; }
#endif
