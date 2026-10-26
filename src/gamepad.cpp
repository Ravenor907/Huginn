#include "gamepad.h"

#include <SDL.h>

#include <QDebug>

namespace
{
constexpr int kDeadzone = 16000;
constexpr int kTrigger = 12000;

QString buttonName(Uint8 b)
{
    switch (b) {
    case SDL_CONTROLLER_BUTTON_A: return QStringLiteral("a");
    case SDL_CONTROLLER_BUTTON_B: return QStringLiteral("b");
    case SDL_CONTROLLER_BUTTON_X: return QStringLiteral("x");
    case SDL_CONTROLLER_BUTTON_Y: return QStringLiteral("y");
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER: return QStringLiteral("lb");
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER: return QStringLiteral("rb");
    case SDL_CONTROLLER_BUTTON_START: return QStringLiteral("start");
    case SDL_CONTROLLER_BUTTON_BACK: return QStringLiteral("back");
    case SDL_CONTROLLER_BUTTON_DPAD_UP: return QStringLiteral("up");
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN: return QStringLiteral("down");
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT: return QStringLiteral("left");
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT: return QStringLiteral("right");
    case SDL_CONTROLLER_BUTTON_LEFTSTICK: return QStringLiteral("ls");
    case SDL_CONTROLLER_BUTTON_RIGHTSTICK: return QStringLiteral("rs");
    }
    return {};
}

bool isDirection(const QString &b)
{
    return b == QLatin1String("up") || b == QLatin1String("down") || b == QLatin1String("left") || b == QLatin1String("right")
        || b == QLatin1String("lt") || b == QLatin1String("rt");
}
}

Gamepad::Gamepad(QObject *parent)
    : QObject(parent)
{
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (SDL_Init(SDL_INIT_GAMECONTROLLER) != 0) {
        qWarning() << "Controller support is off:" << SDL_GetError();
        return;
    }
    m_sdlReady = true;
    for (int i = 0; i < SDL_NumJoysticks(); ++i) {
        if (SDL_IsGameController(i)) {
            open(i);
            break;
        }
    }
    connect(&m_poll, &QTimer::timeout, this, &Gamepad::poll);
    m_poll.start(10);

    m_repeat.setSingleShot(false);
    connect(&m_repeat, &QTimer::timeout, this, [this] {
        if (m_repeatButton.isEmpty())
            return;
        m_repeat.setInterval(70);
        m_repeating = true;
        Q_EMIT pressed(m_repeatButton);
        m_repeating = false;
    });
}

Gamepad::~Gamepad()
{
    if (m_pad)
        SDL_GameControllerClose(m_pad);
    if (m_sdlReady)
        SDL_Quit();
}

void Gamepad::open(int index)
{
    if (m_pad)
        return;
    m_pad = SDL_GameControllerOpen(index);
    if (!m_pad)
        return;
    switch (SDL_GameControllerGetType(m_pad)) {
    case SDL_CONTROLLER_TYPE_PS3:
    case SDL_CONTROLLER_TYPE_PS4:
    case SDL_CONTROLLER_TYPE_PS5:
        m_style = QStringLiteral("playstation");
        break;
    default:
        m_style = QStringLiteral("xbox");
    }
    Q_EMIT connectedChanged();
}

void Gamepad::down(const QString &button, bool repeats)
{
    Q_EMIT pressed(button);
    if (repeats) {
        m_repeatButton = button;
        m_repeat.start(380);
    }
}

void Gamepad::up(const QString &button)
{
    if (m_repeatButton == button) {
        m_repeatButton.clear();
        m_repeat.stop();
    }
    Q_EMIT released(button);
}

void Gamepad::axisAsButton(const QString &button, bool active, bool repeats)
{
    const bool was = m_axisHeld.value(button);
    if (active == was)
        return;
    m_axisHeld[button] = active;
    if (active)
        down(button, repeats);
    else
        up(button);
}

void Gamepad::poll()
{
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
        switch (e.type) {
        case SDL_CONTROLLERDEVICEADDED:
            open(e.cdevice.which);
            break;
        case SDL_CONTROLLERDEVICEREMOVED:
            if (m_pad && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(m_pad)) == e.cdevice.which) {
                SDL_GameControllerClose(m_pad);
                m_pad = nullptr;
                Q_EMIT connectedChanged();
                for (int i = 0; i < SDL_NumJoysticks(); ++i)
                    if (SDL_IsGameController(i)) { open(i); break; }
            }
            break;
        case SDL_CONTROLLERBUTTONDOWN: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                down(b, isDirection(b));
            break;
        }
        case SDL_CONTROLLERBUTTONUP: {
            const QString b = buttonName(e.cbutton.button);
            if (!b.isEmpty())
                up(b);
            break;
        }
        case SDL_CONTROLLERAXISMOTION: {
            const int v = e.caxis.value;
            switch (e.caxis.axis) {
            case SDL_CONTROLLER_AXIS_TRIGGERLEFT:
                axisAsButton(QStringLiteral("lt"), v > kTrigger, true);
                break;
            case SDL_CONTROLLER_AXIS_TRIGGERRIGHT:
                axisAsButton(QStringLiteral("rt"), v > kTrigger, true);
                break;
            case SDL_CONTROLLER_AXIS_LEFTX:
                m_stickX = v / 32767.0;
                Q_EMIT stickChanged();
                axisAsButton(QStringLiteral("left"), v < -kDeadzone, true);
                axisAsButton(QStringLiteral("right"), v > kDeadzone, true);
                break;
            case SDL_CONTROLLER_AXIS_LEFTY:
                m_stickY = v / 32767.0;
                Q_EMIT stickChanged();
                axisAsButton(QStringLiteral("up"), v < -kDeadzone, true);
                axisAsButton(QStringLiteral("down"), v > kDeadzone, true);
                break;
            }
            break;
        }
        }
    }
}

QString Gamepad::glyph(const QString &button) const
{
    const bool ps = m_style == QLatin1String("playstation");
    if (button == QLatin1String("a")) return ps ? QStringLiteral("✕") : QStringLiteral("A");
    if (button == QLatin1String("b")) return ps ? QStringLiteral("○") : QStringLiteral("B");
    if (button == QLatin1String("x")) return ps ? QStringLiteral("□") : QStringLiteral("X");
    if (button == QLatin1String("y")) return ps ? QStringLiteral("△") : QStringLiteral("Y");
    if (button == QLatin1String("lb")) return ps ? QStringLiteral("L1") : QStringLiteral("LB");
    if (button == QLatin1String("rb")) return ps ? QStringLiteral("R1") : QStringLiteral("RB");
    if (button == QLatin1String("lbrb")) return ps ? QStringLiteral("L1 R1") : QStringLiteral("LB RB");
    if (button == QLatin1String("lt")) return ps ? QStringLiteral("L2") : QStringLiteral("LT");
    if (button == QLatin1String("rt")) return ps ? QStringLiteral("R2") : QStringLiteral("RT");
    if (button == QLatin1String("start")) return ps ? QStringLiteral("Options") : QStringLiteral("Menu");
    if (button == QLatin1String("back")) return ps ? QStringLiteral("Share") : QStringLiteral("View");
    if (button == QLatin1String("right")) return QStringLiteral("▶");
    if (button == QLatin1String("left")) return QStringLiteral("◀");
    if (button == QLatin1String("updown")) return QStringLiteral("▲▼");
    return button.toUpper();
}
