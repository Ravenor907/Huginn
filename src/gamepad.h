#pragma once

#include <QHash>
#include <QObject>
#include <QTimer>

struct _SDL_GameController;

// Reads controllers through SDL. In Game Mode Steam hands non-Steam apps a virtual Xbox pad,
// so this is the same path every Steam game uses. D-pad and stick hold to repeat.
class Gamepad : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString style READ style NOTIFY connectedChanged)
    Q_PROPERTY(double stickX READ stickX NOTIFY stickChanged)
    Q_PROPERTY(double stickY READ stickY NOTIFY stickChanged)

public:
    explicit Gamepad(QObject *parent = nullptr);
    ~Gamepad() override;

    bool connected() const { return m_pad != nullptr; }
    QString style() const { return m_style; }
    double stickX() const { return m_stickX; }
    double stickY() const { return m_stickY; }

    // The label for a button prompt in the current controller's style.
    Q_INVOKABLE QString glyph(const QString &button) const;

Q_SIGNALS:
    void pressed(const QString &button);
    void released(const QString &button);
    void connectedChanged();
    void stickChanged();

private:
    void poll();
    void open(int index);
    void down(const QString &button, bool repeats);
    void up(const QString &button);
    void axisAsButton(const QString &button, bool active, bool repeats);

    QTimer m_poll;
    QTimer m_repeat;
    _SDL_GameController *m_pad = nullptr;
    bool m_sdlReady = false;
    QString m_style = QStringLiteral("xbox");
    QString m_repeatButton;
    QHash<QString, bool> m_axisHeld;
    double m_stickX = 0;
    double m_stickY = 0;
};
