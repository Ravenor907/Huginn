#include "msglimits.h"

#include "groupwire.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonValue>
#include <cmath>
#include <cstdio>

const char Limits::kCap[] = "d1";

int Limits::groupDays(const QJsonObject &state)
{
    if (!state.contains(QStringLiteral("kind")))
        return kDefault;
    const int d = state.value(QStringLiteral("days")).toInt(kDefault);
    return d >= kDefault && d <= kLongest ? d : kDefault;
}

int Limits::envelopeDays(const QJsonObject &o)
{
    const QJsonValue v = o.value(QStringLiteral("days"));
    if (!v.isDouble())
        return 0;
    const double d = v.toDouble();
    if (d != std::floor(d) || d < kDefault || d > kLongest)
        return 0;
    return int(d);
}

int Limits::myDays(int chosen, bool close)
{
    if (!close || chosen <= 0)
        return kDefault;
    return qBound(kDefault, chosen, kLongest);
}

int Limits::pairDays(int mine, bool hasD1, int theirs)
{
    if (!hasD1)
        return mine;
    return qMin(mine, theirs > 0 ? theirs : kDefault);
}

qint64 Limits::cutoff(qint64 now, int days) { return now - days * kDay; }

QJsonObject Limits::withLimit(QJsonObject state, const QString &kind, int days)
{
    state.remove(QStringLiteral("kind"));
    state.remove(QStringLiteral("days"));
    if (kind != QLatin1String("family") && kind != QLatin1String("work"))
        return state;   // Standard: neither field, the state any older app takes
    state.insert(QStringLiteral("kind"), kind);
    if (days > kDefault)
        state.insert(QStringLiteral("days"), qMin(days, kLongest));
    return state;
}

QString Limits::kindName(const QString &kind)
{
    if (kind == QLatin1String("family"))
        return QStringLiteral("Family");
    if (kind == QLatin1String("work"))
        return QStringLiteral("Work");
    return QStringLiteral("Standard");
}

bool Limits::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("d1 self-test: FAILED %s\n", qPrintable(what));
    };
    QFile f(vectorsPath);
    const QJsonObject v = f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
    if (v.isEmpty()) {
        std::printf("d1 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QString domain = QLatin1String(GroupWire::kDomain);
    const QJsonObject states = v.value(QStringLiteral("states")).toObject();
    for (const QString &k : states.keys()) {
        const QJsonObject x = states.value(k).toObject();
        const QJsonObject s = x.value(QStringLiteral("state")).toObject();
        check(GroupWire::checkState(s).isEmpty(), QStringLiteral("well-formed ") + k + QStringLiteral(": ") + GroupWire::checkState(s));
        check(GroupWire::signedBytes(domain, s) == x.value(QStringLiteral("signed")).toString().toUtf8(), QStringLiteral("signed bytes ") + k);
        check(GroupWire::hash(s) == x.value(QStringLiteral("hash")).toString(), QStringLiteral("hash ") + k);
        check(GroupWire::verify(s.value(QStringLiteral("by")).toString(), domain, s), QStringLiteral("signature ") + k);
        check(groupDays(s) == x.value(QStringLiteral("days")).toInt(), QStringLiteral("days ") + k);
    }
    const QJsonObject refuse = v.value(QStringLiteral("refuse")).toObject();
    for (const QString &k : refuse.keys())
        check(!GroupWire::checkState(refuse.value(k).toObject()).isEmpty(), QStringLiteral("refuses ") + k);
    for (const QJsonValue &a : v.value(QStringLiteral("accept")).toArray()) {
        const QJsonObject c = a.toObject();
        const QJsonObject next = states.value(c.value(QStringLiteral("new")).toString()).toObject().value(QStringLiteral("state")).toObject();
        const QJsonObject cur = states.value(c.value(QStringLiteral("current")).toString()).toObject().value(QStringLiteral("state")).toObject();
        const bool first = c.value(QStringLiteral("current")).isNull();
        check(GroupWire::accept(first ? nullptr : &cur, next).ok == c.value(QStringLiteral("accept")).toBool(),
              QStringLiteral("accept %1 -> %2").arg(c.value(QStringLiteral("current")).toString(), c.value(QStringLiteral("new")).toString()));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("envelope")).toArray()) {
        const QJsonObject c = a.toObject();
        const int want = c.value(QStringLiteral("days")).isNull() ? 0 : c.value(QStringLiteral("days")).toInt();
        check(envelopeDays(c.value(QStringLiteral("o")).toObject()) == want,
              QStringLiteral("envelope ") + QString::fromUtf8(QJsonDocument(c.value(QStringLiteral("o")).toObject()).toJson(QJsonDocument::Compact)));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("mine")).toArray()) {
        const QJsonObject c = a.toObject();
        const int chosen = c.value(QStringLiteral("chosen")).isNull() ? 0 : c.value(QStringLiteral("chosen")).toInt();
        check(myDays(chosen, c.value(QStringLiteral("close")).toBool()) == c.value(QStringLiteral("days")).toInt(), QStringLiteral("mine %1").arg(chosen));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("pair")).toArray()) {
        const QJsonObject c = a.toObject();
        const int theirs = c.value(QStringLiteral("theirs")).isNull() ? 0 : c.value(QStringLiteral("theirs")).toInt();
        check(pairDays(c.value(QStringLiteral("mine")).toInt(), c.value(QStringLiteral("d1")).toBool(), theirs) == c.value(QStringLiteral("days")).toInt(),
              QStringLiteral("pair %1 %2").arg(c.value(QStringLiteral("mine")).toInt()).arg(theirs));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("sweep")).toArray()) {
        const QJsonObject c = a.toObject();
        const qint64 cut = cutoff(qint64(c.value(QStringLiteral("now")).toDouble()), c.value(QStringLiteral("days")).toInt());
        const QJsonArray t = c.value(QStringLiteral("times")).toArray(), k = c.value(QStringLiteral("kept")).toArray();
        for (int i = 0; i < int(t.size()); ++i)
            check((qint64(t.at(i).toDouble()) >= cut) == k.at(i).toBool(), QStringLiteral("sweep %1 days, time %2").arg(c.value(QStringLiteral("days")).toInt()).arg(i));
    }
    // Building a state: Standard has neither field, so older apps take it; Family keeps its days.
    QJsonObject s{{QStringLiteral("g"), QStringLiteral("x")}};
    check(!withLimit(s, QString(), 30).contains(QStringLiteral("kind")) && !withLimit(s, QString(), 30).contains(QStringLiteral("days")), "Standard has no kind or days");
    check(groupDays(withLimit(s, QStringLiteral("family"), 30)) == 30 && groupDays(withLimit(s, QStringLiteral("work"), 7)) == 7
              && !withLimit(s, QStringLiteral("work"), 7).contains(QStringLiteral("days")),
          "Family and Work keep their days, 7 is left out");
    std::printf("d1 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
