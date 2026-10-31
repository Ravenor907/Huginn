#pragma once

#include <QtGlobal>
#include <vector>

// Small sound helpers for calls, free of Qt Multimedia so the self-test can run them anywhere.
namespace AudioDsp
{
const int kRate = 16000;            // what goes into Opus and comes out of it
const int kFrame = kRate / 50;      // 320 samples: 20 ms

// Linear-interpolation resampler for a mono float stream. Keeps its place between calls, so a
// stream fed in pieces of any size comes out the same as fed in one go.
class Resampler
{
public:
    Resampler(int inRate = kRate, int outRate = kRate) { setRates(inRate, outRate); }
    void setRates(int inRate, int outRate);
    bool passthrough() const { return m_in == m_out; }
    void process(const float *in, int n, std::vector<float> *out);
    void reset() { m_pos = 0; m_last = 0; m_primed = false; }

private:
    int m_in = kRate, m_out = kRate;
    double m_step = 1;     // input samples per output sample
    double m_pos = 0;      // where the next output sample falls, from m_last (0) to the first new sample (1)
    float m_last = 0;
    bool m_primed = false;
};

// The level of a frame in dBFS (full scale 0, silence -100).
double levelDb(const qint16 *pcm, int n);

// 5.5: speaking while the 20 ms level is above -45 dBFS for 2 frames in a row, held for 300 ms after.
class Speaking
{
public:
    // One 20 ms frame; true while speaking.
    bool frame(double levelDb);
    bool on() const { return m_on; }
    void reset() { m_loud = 0; m_hold = 0; m_on = false; }

private:
    int m_loud = 0;
    int m_hold = 0;     // frames left to hold
    bool m_on = false;
};

// A test tone, for the loopback self-test: amplitude 0..1 of full scale.
void tone(qint16 *pcm, int n, double hz, double amplitude, qint64 *phaseSamples);
// Rough frequency of a frame from its zero crossings.
double zeroCrossingHz(const qint16 *pcm, int n, int rate = kRate);

bool selfTest();
}
