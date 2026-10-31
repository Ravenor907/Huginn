#include "audiodsp.h"

#include <cmath>
#include <cstdio>

namespace
{
const double kPi = 3.14159265358979323846;   // M_PI is missing on MSVC
}

void AudioDsp::Resampler::setRates(int inRate, int outRate)
{
    m_in = inRate > 0 ? inRate : kRate;
    m_out = outRate > 0 ? outRate : kRate;
    m_step = double(m_in) / double(m_out);
    reset();
}

void AudioDsp::Resampler::process(const float *in, int n, std::vector<float> *out)
{
    if (n <= 0)
        return;
    if (passthrough()) {
        out->insert(out->end(), in, in + n);
        return;
    }
    int i = 0;
    if (!m_primed) {
        m_last = in[0];
        m_primed = true;
        i = 1;
        m_pos = 0;
    }
    // Output samples fall between m_last (position 0) and in[i] (position 1).
    for (; i < n; ++i) {
        const float next = in[i];
        while (m_pos < 1.0) {
            out->push_back(float(m_last + (next - m_last) * m_pos));
            m_pos += m_step;
        }
        m_pos -= 1.0;
        m_last = next;
    }
}

double AudioDsp::levelDb(const qint16 *pcm, int n)
{
    if (n <= 0)
        return -100;
    double sum = 0;
    for (int i = 0; i < n; ++i)
        sum += double(pcm[i]) * double(pcm[i]);
    const double rms = std::sqrt(sum / n) / 32768.0;
    return rms > 1e-5 ? 20.0 * std::log10(rms) : -100;
}

bool AudioDsp::Speaking::frame(double levelDb)
{
    if (levelDb > -45) {
        if (++m_loud >= 2) {
            m_on = true;
            m_hold = 15;   // 300 ms of frames
        }
    } else {
        m_loud = 0;
        if (m_hold > 0)
            --m_hold;
        if (m_hold == 0)
            m_on = false;
    }
    return m_on;
}

void AudioDsp::tone(qint16 *pcm, int n, double hz, double amplitude, qint64 *phase)
{
    for (int i = 0; i < n; ++i) {
        const double t = double((*phase)++) / kRate;
        pcm[i] = qint16(std::lround(std::sin(2 * kPi * hz * t) * amplitude * 32767.0));
    }
}

double AudioDsp::zeroCrossingHz(const qint16 *pcm, int n, int rate)
{
    int crossings = 0;
    for (int i = 1; i < n; ++i)
        if ((pcm[i - 1] < 0) != (pcm[i] < 0))
            ++crossings;
    return n > 1 ? crossings * double(rate) / (2.0 * (n - 1)) : 0;
}

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}
}

bool AudioDsp::selfTest()
{
    g_fail = 0;
    std::printf("audio helpers\n");
    // 48 kHz to 16 kHz and back: the length is a third, and a 440 Hz tone stays 440 Hz.
    std::vector<float> in(48000);
    for (int i = 0; i < 48000; ++i)
        in[size_t(i)] = float(std::sin(2 * kPi * 440.0 * i / 48000.0) * 0.5);
    Resampler down(48000, 16000);
    std::vector<float> whole;
    down.process(in.data(), int(in.size()), &whole);
    Resampler pieces(48000, 16000);
    std::vector<float> parts;
    for (size_t at = 0; at < in.size(); at += 441)
        pieces.process(in.data() + at, int(std::min<size_t>(441, in.size() - at)), &parts);
    check(std::abs(int(whole.size()) - 16000) <= 2, "48 kHz to 16 kHz: a third as many samples");
    bool same = whole.size() == parts.size();
    for (size_t i = 0; same && i < whole.size(); ++i)
        same = std::abs(whole[i] - parts[i]) < 1e-6f;
    check(same, "fed in pieces, the same output");
    std::vector<qint16> pcm(whole.size());
    for (size_t i = 0; i < whole.size(); ++i)
        pcm[i] = qint16(whole[i] * 32767);
    const double hz = zeroCrossingHz(pcm.data(), int(pcm.size()));
    check(hz > 430 && hz < 450, "the tone keeps its pitch");
    Resampler up(16000, 44100);
    std::vector<float> back;
    up.process(whole.data(), int(whole.size()), &back);
    check(std::abs(int(back.size()) - 44100) <= 3, "16 kHz to 44.1 kHz");

    qint16 quiet[kFrame] = {}, loud[kFrame];
    qint64 phase = 0;
    tone(loud, kFrame, 300, 0.2, &phase);
    check(levelDb(quiet, kFrame) <= -99 && levelDb(loud, kFrame) > -20 && levelDb(loud, kFrame) < -15, "frame levels");
    Speaking s;
    const bool one = s.frame(-20), two = s.frame(-20);
    int held = 0;
    while (s.frame(-80) && held < 100)
        ++held;
    check(!one && two && held == 14, "speaking after 2 loud frames, held 300 ms");
    std::printf("audio helpers: %s\n", g_fail ? "FAILED" : "all good");
    return g_fail == 0;
}
