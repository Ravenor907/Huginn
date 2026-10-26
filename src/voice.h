#pragma once

#include <QBuffer>
#include <QObject>
#include <QTimer>

class QAudioInput;
class QAudioOutput;
class QMediaCaptureSession;
class QMediaPlayer;
class QMediaRecorder;
class Chat;

// Voice messages, the same format as Muninn: bare AAC frames (ADTS), so there's no container and
// nothing in it but the sound. While recording, the sound is written to Huginn's runtime folder
// (in memory, never on the disk), read back and wiped at once; it's only ever saved encrypted.
// Played back from memory too. Up to 5 minutes.
class Voice : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool recording READ recording NOTIFY changed)
    Q_PROPERTY(int elapsed READ elapsed NOTIFY tick)
    Q_PROPERTY(QString playing READ playing NOTIFY changed)
    Q_PROPERTY(qreal progress READ progress NOTIFY tick)
    Q_PROPERTY(QString error READ error NOTIFY changed)

public:
    explicit Voice(Chat *chat, QObject *parent = nullptr);
    ~Voice() override;

    bool recording() const { return m_recording; }
    int elapsed() const;
    QString playing() const { return m_playing; }
    qreal progress() const;
    QString error() const { return m_error; }

    Q_INVOKABLE bool start();
    // Stops and sends to that chat (too short = nothing sent).
    Q_INVOKABLE void finish(const QString &key);
    Q_INVOKABLE void cancel();
    Q_INVOKABLE void play(const QString &id);
    Q_INVOKABLE void stop();
    Q_INVOKABLE static QString length(int ms);

Q_SIGNALS:
    void changed();
    void tick();

private:
    QString tempFile() const;
    Chat *m_chat;
    QMediaCaptureSession *m_session = nullptr;
    QAudioInput *m_input = nullptr;
    QMediaRecorder *m_recorder = nullptr;
    QMediaPlayer *m_player = nullptr;
    QAudioOutput *m_output = nullptr;
    QBuffer m_buffer;
    QByteArray m_sound;
    QTimer m_ticker;
    bool m_recording = false;
    qint64 m_started = 0;
    QString m_sendTo, m_playing, m_error;
};
