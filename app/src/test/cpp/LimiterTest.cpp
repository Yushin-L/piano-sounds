#include "Limiter.h"
#include <iostream>
#include <random>
#include <stdexcept>

static void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

int main() {
    std::mt19937 random(7);
    std::uniform_real_distribution<float> unit(-1, 1);
    for (int rate : {44100, 48000, 96000}) {
        Limiter limiter; limiter.prepare(rate);
        const int latency = limiter.latency();
        check(latency == static_cast<int>(std::lround(rate * 0.002)) - 1, "unexpected look-ahead");

        // Below the threshold the limiter is only a delay.
        std::vector<float> input(rate * 2), output;
        for (float& x : input) x = 0.88f * unit(random);
        output = input;
        limiter.process(output.data(), rate);
        for (size_t i = latency * 2; i < output.size(); ++i)
            check(output[i] == input[i - latency * 2], "quiet audio was altered");

        // Loud bursts of any size stay below the threshold, independent of callback size.
        for (size_t i = 0; i < input.size(); ++i) {
            const size_t burst = i / 2 / (rate / 50);
            input[i] = unit(random) * (burst % 3 == 0 ? 0.3f : burst % 3 == 1 ? 4.0f : 20.0f);
        }
        Limiter whole, split; whole.prepare(rate); split.prepare(rate);
        std::vector<float> a = input, b = input;
        whole.process(a.data(), rate);
        for (int frame = 0, n = 0; frame < rate; frame += n) {
            n = std::min(rate - frame, frame % 2 ? 511 : 37);
            split.process(b.data() + frame * 2, n);
        }
        check(a == b, "output depends on buffer size");
        for (float x : a) check(std::isfinite(x) && std::abs(x) <= 0.89f * 1.00001f, "peak exceeded threshold");
        check(whole.takeReduction() < 0.05f && whole.takeReduction() == 1, "reduction not reported");

        // Gain recovers to unity within a few release time constants.
        std::vector<float> loud(rate / 10 * 2, 8.0f), quiet(rate * 2, 0.5f);
        whole.process(loud.data(), rate / 10);
        whole.process(quiet.data(), rate);
        check(std::abs(quiet.back() - 0.5f) < 1e-3f, "gain did not recover");

        // Reset flushes the delay line so panic is silent immediately.
        whole.process(loud.data(), rate / 10);
        whole.reset();
        std::vector<float> silence(256, 0.0f);
        whole.process(silence.data(), 128);
        check(std::all_of(silence.begin(), silence.end(), [](float x) { return x == 0; }), "reset left stale audio");
    }
    std::cout << "PASS: transparent below threshold, peak bound, buffer independence, release, reset, 3 sample rates\n";
}
