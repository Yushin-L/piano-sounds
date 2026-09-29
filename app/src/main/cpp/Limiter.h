#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

// Look-ahead peak limiter for the stereo master bus. Instead of reshaping the waveform,
// it lowers a smooth, stereo-linked gain just before a peak arrives, so loud chords keep
// clean note attacks. Owned by the audio thread; prepare() allocates and must run only
// while the stream is stopped.
//
// Gain path: required gain per frame -> sliding minimum over the look-ahead window ->
// exponential release -> moving average over the same window. The audio is delayed by
// window - 1 frames, which guarantees |output| <= threshold for every frame.
class Limiter {
public:
    void prepare(int rate, float threshold = 0.89f, float lookaheadSeconds = 0.002f,
                 float releaseSeconds = 0.1f) {
        threshold_ = threshold;
        window_ = std::max(1, static_cast<int>(std::lround(rate * lookaheadSeconds)));
        release_ = 1.0f - std::exp(-1.0f / (rate * releaseSeconds));
        audio_.resize(window_ * 2);
        minIndex_.resize(window_); minValue_.resize(window_); average_.resize(window_);
        reset();
    }
    void reset() {
        std::fill(audio_.begin(), audio_.end(), 0.0f);
        std::fill(average_.begin(), average_.end(), 1.0f);
        sum_ = window_; gain_ = 1; frame_ = 0; head_ = 0; count_ = 0; reduction_ = 1;
    }
    int latency() const { return window_ - 1; }
    // Smallest gain applied since the last call, for diagnostics.
    float takeReduction() { const float r = reduction_; reduction_ = 1; return r; }

    void process(float* stereo, int frames) {
        for (int i = 0; i < frames; ++i, ++frame_) {
            float& l = stereo[i * 2];
            float& r = stereo[i * 2 + 1];
            const float peak = std::max(std::abs(l), std::abs(r));
            const float required = peak > threshold_ ? threshold_ / peak : 1.0f;

            // Monotonic queue: the front holds the minimum over the last window_ frames.
            if (count_ && minIndex_[head_] + window_ <= frame_) { head_ = (head_ + 1) % window_; --count_; }
            while (count_ && minValue_[back()] >= required) --count_;
            const int slot = (head_ + count_++) % window_;
            minIndex_[slot] = frame_; minValue_[slot] = required;
            const float target = minValue_[head_];

            gain_ = target < gain_ ? target : gain_ + (target - gain_) * release_;
            const int ring = static_cast<int>(frame_ % window_);
            sum_ += gain_ - average_[ring];
            average_[ring] = gain_;
            const float gain = std::min(1.0f, static_cast<float>(sum_ / window_));
            reduction_ = std::min(reduction_, gain);

            // Store this frame and emit the one from window_ - 1 frames ago.
            audio_[ring * 2] = l; audio_[ring * 2 + 1] = r;
            const int out = static_cast<int>((frame_ + 1) % window_);
            l = audio_[out * 2] * gain;
            r = audio_[out * 2 + 1] * gain;
        }
    }

private:
    int back() const { return (head_ + count_ - 1) % window_; }
    float threshold_ = 0.89f, release_ = 0, gain_ = 1, reduction_ = 1;
    int window_ = 1, head_ = 0, count_ = 0;
    uint64_t frame_ = 0;
    double sum_ = 1;
    std::vector<float> audio_, minValue_, average_;
    std::vector<uint64_t> minIndex_;
};
