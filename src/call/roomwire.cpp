#include "roomwire.h"

#include "../groupwire.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <algorithm>
#include <cmath>
#include <cstdio>

const char RoomWire::kDomain[] = "huginn-muninn vroom v1";

namespace
{
int g_fail = 0;
void expect(bool ok, const QString &what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", qPrintable(what));
    if (!ok)
        ++g_fail;
}

const QStringList &types()
{
    static const QStringList t{QStringLiteral("vjoin"), QStringLiteral("vleave"), QStringLiteral("vring"), QStringLiteral("vdecline"), QStringLiteral("full")};
    return t;
}

QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

bool RoomWire::isId(const QString &s, int bytes)
{
    if (s.size() != (4 * bytes + 2) / 3)
        return false;
    for (const QChar c : s) {
        const ushort u = c.unicode();
        if (!((u >= 'A' && u <= 'Z') || (u >= 'a' && u <= 'z') || (u >= '0' && u <= '9') || u == '-' || u == '_'))
            return false;
    }
    const QByteArray raw = QByteArray::fromBase64(s.toLatin1(), QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals);
    return raw.size() == bytes && QString::fromLatin1(raw.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals)) == s;
}

bool RoomWire::isRoomId(const QString &ch) { return isId(ch, 8) || isId(ch, 16); }
bool RoomWire::isGroupCall(const QString &ch) { return isId(ch, 16); }
QString RoomWire::newGroupCallId() { return GroupWire::newGroupId(); }

QJsonObject RoomWire::make(const QString &t, const QString &g, const QString &ch, qint64 ts, const std::function<QByteArray(const QByteArray &)> &sign)
{
    QJsonObject o{{QStringLiteral("t"), t}, {QStringLiteral("call"), QStringLiteral("-")}, {QStringLiteral("g"), g},
                  {QStringLiteral("ch"), ch}, {QStringLiteral("ts"), double(ts)}};
    const QByteArray bytes = GroupWire::signedBytes(QString::fromLatin1(kDomain), o);
    const QByteArray sig = bytes.isEmpty() || !sign ? QByteArray() : sign(bytes);
    o.insert(QStringLiteral("sig"), QString::fromLatin1(sig.toBase64()));
    return o;
}

QString RoomWire::check(const QJsonObject &m)
{
    static const QStringList fields{QStringLiteral("t"), QStringLiteral("call"), QStringLiteral("g"), QStringLiteral("ch"), QStringLiteral("ts"), QStringLiteral("sig")};
    if (m.size() != fields.size())
        return QStringLiteral("fields");
    for (const QString &f : fields)
        if (!m.contains(f))
            return QStringLiteral("fields");
    if (!m.value(QStringLiteral("t")).isString() || !types().contains(str(m, "t")))
        return QStringLiteral("type");
    if (!m.value(QStringLiteral("call")).isString() || str(m, "call") != QLatin1String("-"))
        return QStringLiteral("call");
    if (!m.value(QStringLiteral("g")).isString() || !isId(str(m, "g"), 16))
        return QStringLiteral("group id");
    if (!m.value(QStringLiteral("ch")).isString() || !isRoomId(str(m, "ch")))
        return QStringLiteral("room id");
    const QJsonValue ts = m.value(QStringLiteral("ts"));
    const double d = ts.toDouble(-1);
    if (!ts.isDouble() || d < 0 || d >= 9007199254740992.0 || std::floor(d) != d)
        return QStringLiteral("ts");
    return QString();
}

QString RoomWire::accept(const QJsonObject *state, const QString &me, const QString &from, const QJsonObject &m, qint64 now)
{
    const QString bad = check(m);
    if (!bad.isEmpty())
        return QStringLiteral("malformed: ") + bad;
    if (!GroupWire::verify(from, QString::fromLatin1(kDomain), m))
        return QStringLiteral("bad signature");
    if (!state || state->isEmpty() || str(*state, "g") != str(m, "g"))
        return QStringLiteral("unknown group");
    if (from == me)
        return QStringLiteral("from myself");
    if (!GroupWire::isMember(*state, from))
        return QStringLiteral("sender not a member");
    if (!GroupWire::isMember(*state, me))
        return QStringLiteral("not a member");
    const QString ch = str(m, "ch");
    if (ch.size() == 11 && str(GroupWire::channel(*state, ch), "t") != QLatin1String("voice"))
        return QStringLiteral("not a voice room");
    const qint64 ts = qint64(m.value(QStringLiteral("ts")).toDouble());
    if (ts < now - kFreshPastMs || ts > now + kFreshAheadMs)
        return QStringLiteral("stale");
    return QStringLiteral("ok");
}

// ------------------------------------------------------------------ presence

bool RoomWire::Presence::apply(const QString &from, const QJsonObject &m, qint64 now)
{
    const QString t = str(m, "t");
    if (t != QLatin1String("vjoin") && t != QLatin1String("vleave"))
        return false;
    const QString g = str(m, "g"), ch = str(m, "ch");
    const qint64 ts = qint64(m.value(QStringLiteral("ts")).toDouble());
    const QString k = g + QLatin1Char(' ') + ch + QLatin1Char(' ') + from;
    auto it = m_entries.find(k);
    if (it != m_entries.end() && ts <= it->ts)
        return false;
    Entry e;
    e.g = g;
    e.ch = ch;
    e.key = from;
    e.in = t == QLatin1String("vjoin");
    e.ts = ts;
    e.seen = now;
    m_entries.insert(k, e);
    return true;
}

QStringList RoomWire::Presence::in(const QString &g, const QString &ch, qint64 now) const
{
    QStringList out;
    for (const Entry &e : m_entries)
        if (e.g == g && e.ch == ch && e.in && now - e.seen <= kPresenceTtlMs)
            out << e.key;
    std::sort(out.begin(), out.end());
    return out;
}

QHash<QString, QStringList> RoomWire::Presence::rooms(const QString &g, qint64 now) const
{
    QHash<QString, QStringList> out;
    for (const Entry &e : m_entries)
        if (e.g == g && e.in && now - e.seen <= kPresenceTtlMs)
            out[e.ch] << e.key;
    for (auto it = out.begin(); it != out.end(); ++it)
        std::sort(it->begin(), it->end());
    return out;
}

void RoomWire::Presence::forgetGroup(const QString &g)
{
    for (auto it = m_entries.begin(); it != m_entries.end();)
        it = it->g == g ? m_entries.erase(it) : std::next(it);
}

// ------------------------------------------------------------------ self-test

bool RoomWire::selfTest(const QString &vectorsPath)
{
    g_fail = 0;
    std::printf("rooms: %s\n", qPrintable(vectorsPath));
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::printf("  FAIL can't read the vectors\n");
        return false;
    }
    const QJsonObject all = QJsonDocument::fromJson(f.readAll()).object();
    const QJsonObject r = all.value(QStringLiteral("room")).toObject();
    const QJsonObject keys = all.value(QStringLiteral("keys")).toObject();
    const QJsonObject states = all.value(QStringLiteral("group")).toObject().value(QStringLiteral("states")).toObject();
    const QJsonObject msgs = r.value(QStringLiteral("messages")).toObject();
    expect(!msgs.isEmpty() && all.value(QStringLiteral("domains")).toObject().value(QStringLiteral("room")).toString() == QLatin1String(kDomain),
          QStringLiteral("room section and domain"));
    expect(r.value(QStringLiteral("maxPeople")).toInt() == kMaxPeople && qint64(r.value(QStringLiteral("presenceTtlMs")).toDouble()) == kPresenceTtlMs
              && qint64(r.value(QStringLiteral("freshPastMs")).toDouble()) == kFreshPastMs && qint64(r.value(QStringLiteral("freshAheadMs")).toDouble()) == kFreshAheadMs,
          QStringLiteral("limits: 8 people, 60 s / 120 s fresh, 25 minutes presence"));
    int signedOk = 0;
    for (auto it = msgs.begin(); it != msgs.end(); ++it) {
        const QJsonObject x = it.value().toObject();
        if (GroupWire::signedBytes(QString::fromLatin1(kDomain), x.value(QStringLiteral("msg")).toObject()) == x.value(QStringLiteral("signed")).toString().toUtf8())
            ++signedOk;
        else
            expect(false, QStringLiteral("signed bytes of ") + it.key());
    }
    expect(signedOk == msgs.size(), QStringLiteral("signed bytes of %1 room messages").arg(msgs.size()));
    auto keyOf = [&](const QJsonValue &v) { return keys.value(v.toString()).toString(); };
    const QJsonArray acc = r.value(QStringLiteral("accept")).toArray();
    int accOk = 0;
    for (const QJsonValue &v : acc) {
        const QJsonObject a = v.toObject();
        const QJsonObject st = a.value(QStringLiteral("state")).isString()
            ? states.value(a.value(QStringLiteral("state")).toString()).toObject().value(QStringLiteral("state")).toObject() : QJsonObject();
        const QString got = accept(st.isEmpty() ? nullptr : &st, keyOf(a.value(QStringLiteral("me"))), keyOf(a.value(QStringLiteral("from"))),
                                   msgs.value(str(a, "msg")).toObject().value(QStringLiteral("msg")).toObject(), qint64(a.value(QStringLiteral("now")).toDouble()));
        if (got == str(a, "result"))
            ++accOk;
        else
            expect(false, QStringLiteral("accept %1: wanted %2, got %3").arg(str(a, "msg"), str(a, "result"), got));
    }
    expect(accOk == acc.size() && accOk > 0, QStringLiteral("%1 acceptance checks").arg(acc.size()));
    Presence p;
    const QString g = str(r, "g"), ch = str(r, "voiceRoom");
    const QJsonArray steps = r.value(QStringLiteral("presence")).toArray();
    int presOk = 0;
    for (const QJsonValue &v : steps) {
        const QJsonObject s = v.toObject();
        const qint64 now = qint64(s.value(QStringLiteral("now")).toDouble());
        bool changed = false;
        if (s.value(QStringLiteral("msg")).isString())
            changed = p.apply(keyOf(s.value(QStringLiteral("from"))), msgs.value(str(s, "msg")).toObject().value(QStringLiteral("msg")).toObject(), now);
        QStringList want;
        for (const QJsonValue &k : s.value(QStringLiteral("in")).toArray())
            want << keyOf(k);
        std::sort(want.begin(), want.end());
        if (changed == s.value(QStringLiteral("changed")).toBool() && p.in(g, ch, now) == want)
            ++presOk;
        else
            expect(false, QStringLiteral("presence step at %1").arg(now));
    }
    expect(presOk == steps.size() && presOk > 0, QStringLiteral("%1 presence steps").arg(steps.size()));
    int glareOk = 0;
    const QJsonArray glare = r.value(QStringLiteral("glare")).toArray();
    for (const QJsonValue &v : glare) {
        const QJsonObject x = v.toObject();
        if (offerWins(keyOf(x.value(QStringLiteral("a"))), keyOf(x.value(QStringLiteral("b")))) == keyOf(x.value(QStringLiteral("wins"))))
            ++glareOk;
    }
    expect(glareOk == glare.size() && glareOk > 0, QStringLiteral("glare: the lower chat key's offer wins"));
    expect(!full(6) && full(7), QStringLiteral("full at 7 peers (8 people)"));
    expect(isGroupCall(newGroupCallId()) && isId(str(r, "voiceRoom"), 8) && !isRoomId(QStringLiteral("main")), QStringLiteral("room ids"));
    std::printf("rooms: %s\n", g_fail == 0 ? "all good" : "FAILED");
    return g_fail == 0;
}
