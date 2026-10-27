#include "callmanager.h"

#include "audioio.h"
#include "callcrypto.h"
#include "callmedia.h"
#include "callsession.h"
#include "callsignal.h"
#include "opuscodec.h"
#include "turnclient.h"
#include "../vault.h"

#include <QDateTime>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSoundEffect>
#include <QUrl>

#ifdef Q_OS_LINUX
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusReply>
#include <QVariantMap>
#else
#include "../platform/notify.h"
#endif

namespace
{
const char kFile[] = "calls.json";
const int kMaxHistory = 200;
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
QString str(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

CallManager::CallManager(CallSignal *signal, Vault *vault, QObject *parent)
    : QObject(parent), m_signal(signal), m_vault(vault), m_relay(new RelayCredentials(this))
{
    connect(m_signal, &CallSignal::received, this, &CallManager::onSignal);
    connect(m_signal, &CallSignal::mediaReceived, this, [this](const QString &from, const QByteArray &pkt) {
        if (m_session && m_session->peerKey() == from && m_session->media())
            m_session->media()->receive(pkt, CallMedia::Ble);
    });
    m_ticker.setInterval(1000);
    connect(&m_ticker, &QTimer::timeout, this, &CallManager::tick);
    m_clear.setSingleShot(true);
    m_clear.setInterval(2500);
    connect(&m_clear, &QTimer::timeout, this, [this] {
        m_endText.clear();
        Q_EMIT changed();
    });
    // The raven call, every 3 s while someone is calling.
    m_ringtone = new QSoundEffect(this);
    m_ringtone->setSource(QUrl(QStringLiteral("qrc:/qt/qml/Huginn/assets/raven_message.wav")));
    m_ringer.setInterval(3000);
    connect(&m_ringer, &QTimer::timeout, m_ringtone, &QSoundEffect::play);
    if (m_vault) {
        connect(m_vault, &Vault::opened, this, &CallManager::load);
        // Panic lock: the call ends at once and nothing about it stays on screen.
        connect(m_vault, &Vault::panicked, this, [this] {
            if (m_session)
                m_session->hangup();
            m_endText.clear();
            Q_EMIT changed();
        });
        if (!m_vault->locked())
            load();
    }
#ifdef Q_OS_LINUX
    QDBusConnection::sessionBus().connect(QString(), QStringLiteral("/org/freedesktop/Notifications"), QStringLiteral("org.freedesktop.Notifications"),
                                          QStringLiteral("ActionInvoked"), this, SLOT(onNotificationAction(uint, QString)));
#endif
}

CallManager::~CallManager()
{
    if (m_session)
        m_session->hangup();
}

bool CallManager::available() const { return opusAvailable(); }

// ------------------------------------------------------------------ state for QML

QString CallManager::state() const
{
    if (!m_session || m_session->state() == CallSession::Ended)
        return m_endText.isEmpty() ? QStringLiteral("idle") : QStringLiteral("ended");
    switch (m_session->state()) {
    case CallSession::Calling: return QStringLiteral("calling");
    case CallSession::Ringing: return QStringLiteral("ringing");
    case CallSession::Incoming: return QStringLiteral("incoming");
    case CallSession::Connecting: return QStringLiteral("connecting");
    case CallSession::Active: return QStringLiteral("active");
    case CallSession::Reconnecting: return QStringLiteral("reconnecting");
    case CallSession::Ended: break;
    }
    return QStringLiteral("idle");
}

bool CallManager::inCall() const
{
    const QString s = state();
    return s != QLatin1String("idle") && s != QLatin1String("incoming");
}

QString CallManager::peerKey() const { return m_session ? m_session->peerKey() : m_lastPeer; }

QString CallManager::peerName() const
{
    const QString k = peerKey();
    if (k.isEmpty())
        return QString();
    const QString n = m_signal->nameOf(k);
    return n.isEmpty() ? QStringLiteral("Someone") : n;
}

QString CallManager::statusText() const
{
    const QString s = state();
    if (s == QLatin1String("calling")) return QStringLiteral("Calling…");
    if (s == QLatin1String("ringing")) return QStringLiteral("Ringing…");
    if (s == QLatin1String("incoming")) return QStringLiteral("Incoming call");
    if (s == QLatin1String("connecting")) return QStringLiteral("Connecting…");
    if (s == QLatin1String("reconnecting")) return QStringLiteral("Reconnecting…");
    if (s == QLatin1String("ended")) return m_endText;
    if (s == QLatin1String("active")) return duration(elapsed());
    return QString();
}

int CallManager::elapsed() const { return m_session ? int(m_session->activeMs() / 1000) : 0; }
bool CallManager::muted() const { return m_session && m_session->muted(); }
bool CallManager::peerMuted() const { return m_session && m_session->peerMuted(); }
bool CallManager::peerSpeaking() const { return m_session && m_session->media() && m_session->media()->speaking(); }
bool CallManager::relayLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Relay); }
bool CallManager::wifiLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Wifi); }
bool CallManager::bleLive() const { return m_session && m_session->media() && m_session->media()->live(CallMedia::Ble); }

QString CallManager::outputName() const
{
    AudioIO *a = m_session && m_session->media() ? m_session->media()->audio() : nullptr;
    return a ? a->outputName() : QStringLiteral("Default");
}

QString CallManager::duration(int s) const
{
    if (s < 0)
        s = 0;
    const int h = s / 3600, m = (s / 60) % 60, sec = s % 60;
    const QString mmss = QStringLiteral("%1:%2").arg(m, h ? 2 : 1, 10, QLatin1Char('0')).arg(sec, 2, 10, QLatin1Char('0'));
    return h ? QStringLiteral("%1:%2").arg(h).arg(mmss) : mmss;
}

int CallManager::missedCount() const
{
    int n = 0;
    for (const QVariant &v : m_history) {
        const QVariantMap e = v.toMap();
        if (e.value(QStringLiteral("missed")).toBool() && e.value(QStringLiteral("started")).toLongLong() > m_seen)
            ++n;
    }
    return n;
}

// ------------------------------------------------------------------ actions

bool CallManager::canCall(const QString &key) const { return available() && !key.isEmpty() && m_signal->canCall(key); }

void CallManager::getLogin(std::function<void(const TurnLogin &, const QString &)> done)
{
    m_relay->get(QByteArray::fromBase64(m_signal->myKey().toLatin1()), std::move(done));
}

QString CallManager::call(const QString &key)
{
    if (!available())
        return QStringLiteral("Calls aren't available in this build.");
    if (m_session && m_session->state() != CallSession::Ended)
        return QStringLiteral("You're already in a call.");
    if (!m_signal->canCall(key))
        return QStringLiteral("Needs Muninn or Huginn 0.6");
    auto *s = new CallSession(m_signal, key, [this](auto done) { getLogin(std::move(done)); }, this);
    adopt(s);
    m_started = nowMs();
    s->start();
    return QString();
}

void CallManager::adopt(CallSession *s)
{
    if (m_session)
        m_session->deleteLater();
    m_session = s;
    m_endText.clear();
    m_clear.stop();
    m_lastPeer = s->peerKey();
    connect(s, &CallSession::stateChanged, this, [this, s] {
        if (s != m_session)
            return;
        const bool active = s->state() == CallSession::Active || s->state() == CallSession::Reconnecting;
        if (active && !m_ticker.isActive())
            m_ticker.start();
        if (s->state() != CallSession::Incoming) {
            ring(false);
            notifyIncoming(false);
        }
        Q_EMIT changed();
    });
    connect(s, &CallSession::pathsChanged, this, &CallManager::changed);
    connect(s, &CallSession::mutedChanged, this, &CallManager::changed);
    connect(s, &CallSession::speakingChanged, this, &CallManager::changed);
    connect(s, &CallSession::ended, this, &CallManager::sessionEnded);
    Q_EMIT changed();
}

void CallManager::answer()
{
    if (m_session && m_session->state() == CallSession::Incoming) {
        ring(false);
        notifyIncoming(false);
        m_started = nowMs();
        m_session->answer();
    }
}

void CallManager::decline()
{
    if (m_session && m_session->state() == CallSession::Incoming)
        m_session->decline();
}

void CallManager::hangup()
{
    if (!m_session)
        return;
    if (m_session->state() == CallSession::Incoming)
        m_session->decline();
    else
        m_session->hangup();
}

void CallManager::toggleMute()
{
    if (m_session)
        m_session->setMuted(!m_session->muted());
}

void CallManager::nextOutput()
{
    AudioIO *a = m_session && m_session->media() ? m_session->media()->audio() : nullptr;
    if (!a)
        return;
    a->nextOutput();
    Q_EMIT changed();
}

void CallManager::sessionEnded()
{
    CallSession *s = m_session;
    if (!s)
        return;
    ring(false);
    notifyIncoming(false);
    m_ticker.stop();
    const QString text = s->endText();
    const bool missed = !s->outgoing() && !s->wasAnswered() && text != QLatin1String("Declined");
    const QString result = s->wasAnswered() ? QStringLiteral("answered") : missed ? QStringLiteral("missed") : text;
    addHistory(s->peerKey(), s->outgoing(), result, m_started ? m_started : nowMs(), int(s->activeMs() / 1000));
    m_handled.insert(s->callId());
    m_endText = text;
    m_clear.start();
    Q_EMIT finished(missed ? QStringLiteral("Missed call from %1").arg(peerName()) : text);
    Q_EMIT changed();
}

// ------------------------------------------------------------------ signaling in

void CallManager::onSignal(const QString &from, const QJsonObject &msg)
{
    const QString t = str(msg, "t"), id = str(msg, "call");
    if (id == QLatin1String("-") || msg.contains(QStringLiteral("g")))
        return;   // voice rooms (section 6) aren't in Huginn yet
    if (m_session && m_session->callId() == id && m_session->peerKey() == from) {
        m_session->handle(msg);
        return;
    }
    if (t != QLatin1String("offer") || m_handled.contains(id))
        return;
    const QString why = CallSession::checkOffer(msg, from, nowMs());
    if (why == QLatin1String("signature") || why == QLatin1String("malformed"))
        return;   // refused: not from who it says
    if (!available() || why == QLatin1String("codec")) {
        m_handled.insert(id);
        m_signal->send(from, QJsonObject{{QStringLiteral("t"), QStringLiteral("decline")}, {QStringLiteral("call"), id}});
        return;
    }
    if (why == QLatin1String("stale")) {
        // An old offer that waited on a relay: a missed call, never a ringing one.
        m_handled.insert(id);
        addHistory(from, false, QStringLiteral("missed"), qint64(msg.value(QStringLiteral("ts")).toDouble()), 0);
        return;
    }
    if (m_session && m_session->state() != CallSession::Ended) {
        m_handled.insert(id);
        const QJsonObject busy{{QStringLiteral("t"), QStringLiteral("busy")}, {QStringLiteral("call"), id}};
        m_signal->send(from, busy);
        QPointer<CallSignal> sig = m_signal;
        for (int ms : {1000, 2000})
            QTimer::singleShot(ms, this, [sig, from, busy] {
                if (sig)
                    sig->send(from, busy);
            });
        addHistory(from, false, QStringLiteral("missed"), nowMs(), 0);
        return;
    }
    auto *s = new CallSession(m_signal, from, [this](auto done) { getLogin(std::move(done)); }, this);
    adopt(s);
    m_started = nowMs();
    s->takeOffer(msg);
    ring(true);
    notifyIncoming(true);
    Q_EMIT incoming(peerName());
}

// ------------------------------------------------------------------ ringing

void CallManager::ring(bool on)
{
    if (on) {
        m_ringtone->play();
        m_ringer.start();
    } else {
        m_ringer.stop();
        m_ringtone->stop();
    }
}

void CallManager::notifyIncoming(bool on)
{
    const bool wanted = on && QGuiApplication::applicationState() != Qt::ApplicationActive;
#ifdef Q_OS_LINUX
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    if (!wanted) {
        if (m_notification)
            n.call(QStringLiteral("CloseNotification"), m_notification);
        m_notification = 0;
        return;
    }
    const bool preview = !m_vault || !m_vault->screenLocked();
    QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                      {QStringLiteral("resident"), true}};
    QDBusReply<uint> r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), m_notification, QStringLiteral("io.github.ravenor907.Huginn"),
                                QStringLiteral("Incoming call"), preview ? peerName() + QStringLiteral(" is calling") : QStringLiteral("Someone is calling"),
                                QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                hints, 45000);
    if (r.isValid())
        m_notification = r.value();
#else
    // Windows: a plain toast (no buttons); answering happens in the app.
    if (wanted)
        Platform::notify(QStringLiteral("Incoming call"), (!m_vault || !m_vault->screenLocked()) ? peerName() + QStringLiteral(" is calling") : QStringLiteral("Someone is calling"), 45000);
#endif
}

void CallManager::onNotificationAction(uint id, const QString &action)
{
    if (!m_notification || id != m_notification)
        return;
    m_notification = 0;
    if (action == QLatin1String("answer"))
        answer();
    else if (action == QLatin1String("decline"))
        decline();
}

// ------------------------------------------------------------------ history

void CallManager::addHistory(const QString &key, bool out, const QString &result, qint64 started, int seconds)
{
    QVariantMap e{{QStringLiteral("id"), CallCrypto::newCallId()},
                  {QStringLiteral("key"), key},
                  {QStringLiteral("name"), m_signal->nameOf(key)},
                  {QStringLiteral("out"), out},
                  {QStringLiteral("result"), result},
                  {QStringLiteral("started"), started},
                  {QStringLiteral("seconds"), seconds},
                  {QStringLiteral("missed"), result == QLatin1String("missed")}};
    m_history.prepend(e);
    while (m_history.size() > kMaxHistory)
        m_history.removeLast();
    save();
    Q_EMIT historyChanged();
}

void CallManager::markSeen()
{
    m_seen = nowMs();
    save();
    Q_EMIT historyChanged();
}

void CallManager::deleteEntry(const QString &id)
{
    for (int i = 0; i < m_history.size(); ++i)
        if (m_history.at(i).toMap().value(QStringLiteral("id")).toString() == id) {
            m_history.removeAt(i);
            break;
        }
    save();
    Q_EMIT historyChanged();
}

void CallManager::clearHistory()
{
    m_history.clear();
    save();
    Q_EMIT historyChanged();
}

void CallManager::load()
{
    if (!m_vault || m_vault->locked())
        return;
    const QJsonObject o = QJsonDocument::fromJson(m_vault->read(QString::fromLatin1(kFile))).object();
    // Calls logged while the vault was locked come first, then what was saved.
    QVariantList saved = o.value(QStringLiteral("calls")).toArray().toVariantList();
    if (!m_loaded)
        m_history.append(saved);
    m_seen = qMax(m_seen, qint64(o.value(QStringLiteral("seen")).toDouble()));
    m_loaded = true;
    save();
    Q_EMIT historyChanged();
}

void CallManager::save()
{
    if (!m_vault || m_vault->locked() || !m_loaded)
        return;
    const QJsonObject o{{QStringLiteral("seen"), double(m_seen)}, {QStringLiteral("calls"), QJsonArray::fromVariantList(m_history)}};
    m_vault->write(QString::fromLatin1(kFile), QJsonDocument(o).toJson(QJsonDocument::Compact));
}
