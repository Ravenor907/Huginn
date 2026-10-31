#pragma once

#include <QByteArray>

namespace Platform
{
// Windows: locks data to this Windows account on this computer (DPAPI), so a copy of the file is no use
// anywhere else. True with the result in *out. Elsewhere there's nothing like it: false.
bool protect(const QByteArray &data, QByteArray *out);
// Opens what protect() made. False when it can't (another account or computer, or damaged).
bool unprotect(const QByteArray &data, QByteArray *out);
}
