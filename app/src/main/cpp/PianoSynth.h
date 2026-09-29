#pragma once
#include "Limiter.h"
#include <android/asset_manager.h>
#include <array>
#include <cstdint>
#include <vector>

struct PianoSample {
    int root = 0, layer = 0, rate = 24000;
    std::vector<int16_t> pcm;
};

class PianoSynth {
public:
    static constexpr int kVoices = 96;
    PianoSynth() { limiter_.prepare(rate_); }
    bool load(AAssetManager* assets);
    // Allocates; call only while the audio stream is stopped.
    void sampleRate(int rate) { rate_ = rate; limiter_.prepare(rate); }
    void midi(int status, int a, int b);
    void panic();
    void render(float* output, int frames, float targetVolume);
    int activeVoices() const;
    bool sustained(int channel) const { return sustain_[channel]; }
    size_t sampleCount() const { return samples_.size(); }
private:
    struct Voice {
        const PianoSample* sample = nullptr;
        int note = 0, channel = 0;
        uint64_t age = 0;
        double position = 0, step = 1;
        float gain = 0, envelope = 1, release = 1, attack = 0;
        float lastL = 0, lastR = 0, stolenL = 0, stolenR = 0, stolenFade = 0;
        bool held = false, releasing = false;
    };
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void release(Voice& voice);
    std::vector<PianoSample> samples_;
    std::array<Voice, kVoices> voices_{};
    std::array<bool, 16> sustain_{};
    int rate_ = 48000;
    uint64_t clock_ = 0;
    float volume_ = 0.7f;
    Limiter limiter_;
};
