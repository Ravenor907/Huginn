#include "powerwatch.h"

#include <QAbstractNativeEventFilter>
#include <QCoreApplication>
#include <QPointer>

#include <windows.h>

namespace
{
class PowerFilter : public QAbstractNativeEventFilter
{
public:
    PowerFilter(QObject *context, std::function<void()> suspended, std::function<void()> resumed)
        : m_context(context), m_suspended(std::move(suspended)), m_resumed(std::move(resumed)) {}

    bool nativeEventFilter(const QByteArray &type, void *message, qintptr *) override
    {
        if (type != "windows_generic_MSG" || !m_context)
            return false;
        const MSG *msg = static_cast<const MSG *>(message);
        if (msg->message != WM_POWERBROADCAST)
            return false;
        // Every Huginn window hears the same broadcast: act once per sleep and once per wake.
        if (msg->wParam == PBT_APMSUSPEND) {
            if (!m_asleep) {
                m_asleep = true;
                if (m_suspended)
                    m_suspended();
            }
        } else if (msg->wParam == PBT_APMRESUMEAUTOMATIC || msg->wParam == PBT_APMRESUMESUSPEND) {
            if (m_asleep) {
                m_asleep = false;
                if (m_resumed)
                    m_resumed();
            }
        }
        return false;   // Qt and Windows still see it
    }

private:
    QPointer<QObject> m_context;
    std::function<void()> m_suspended, m_resumed;
    bool m_asleep = false;
};
}

namespace Platform
{
void watchPower(QObject *context, std::function<void()> suspended, std::function<void()> resumed)
{
    auto *app = QCoreApplication::instance();
    if (!app || !context)
        return;
    auto *filter = new PowerFilter(context, std::move(suspended), std::move(resumed));
    app->installNativeEventFilter(filter);
    QObject::connect(context, &QObject::destroyed, app, [app, filter] {
        app->removeNativeEventFilter(filter);
        delete filter;
    });
}
}
