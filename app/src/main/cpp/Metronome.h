#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

// Owned by the audio thread. prepare() runs only while the stream is stopped.
class Metronome {
public:
    void prepare(int rate) {
        rate_ = rate;
        click_.resize(std::max(2, rate * 24 / 1000));
        constexpr double pi = 3.14159265358979323846;
        for (size_t i = 0; i < click_.size(); ++i) {
            const double time = static_cast<double>(i) / rate;
            click_[i] = static_cast<float>(std::sin(2 * pi * 1800 * time) *
                std::sin(pi * i / (click_.size() - 1)) * std::exp(-100 * time));
        }
        reset();
    }
    void reset() {
        enabled_ = false; phase_ = 0; blend_ = 0; level_ = 0;
        cursor_ = click_.size(); beats_ = 0; frame_ = 0; lastBeat_ = 0;
    }
    void mix(float* stereo, int frames, bool enabled, int bpm, int volume) {
        bpm = std::clamp(bpm, 40, 240);
        const float level = std::clamp(volume, 0, 100) / 100.0f;
        const int64_t period = static_cast<int64_t>(rate_) * 60;
        if (enabled && !enabled_) phase_ = period; // First click starts immediately.
        enabled_ = enabled;
        const float ramp = 1.0f / (rate_ * 0.005f);
        for (int i = 0; i < frames; ++i, ++frame_) {
            if (enabled) {
                if (phase_ >= period) {
                    phase_ -= period; cursor_ = 0; ++beats_; lastBeat_ = frame_;
                }
                // Integer accumulation preserves fractional beat lengths across callbacks
                // and tempo changes, without long-term rounding drift.
                phase_ += bpm;
            }
            blend_ += std::clamp((enabled ? 1.0f : 0.0f) - blend_, -ramp, ramp);
            level_ += std::clamp(level - level_, -ramp, ramp);
            const float click = cursor_ < click_.size() ? click_[cursor_++] : 0.0f;
            const float amount = 0.25f * blend_ * level_;
            // Reserve click headroom with a smoothed linear mix. Both inputs are bounded
            // by +/-1, so even loud chords + clicks cannot clip or need new saturation.
            for (int channel = 0; channel < 2; ++channel)
                stereo[i * 2 + channel] = stereo[i * 2 + channel] * (1 - amount) + click * amount;
        }
    }
    uint32_t beats() const { return beats_; }
    uint64_t lastBeatFrame() const { return lastBeat_; }
private:
    int rate_ = 48000;
    std::vector<float> click_;
    size_t cursor_ = 0;
    int64_t phase_ = 0;
    float blend_ = 0, level_ = 0;
    bool enabled_ = false;
    uint32_t beats_ = 0;
    uint64_t frame_ = 0, lastBeat_ = 0;
};
