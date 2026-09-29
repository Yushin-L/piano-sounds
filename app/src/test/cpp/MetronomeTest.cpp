#include "Metronome.h"
#include <array>
#include <iostream>
#include <stdexcept>

static void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

int main() {
    // Ten minutes at fractional and extreme tempos, using irregular callback sizes.
    for (int rate : {44100, 48000, 96000}) for (int bpm : {40, 137, 240}) {
        Metronome metronome; metronome.prepare(rate);
        std::array<float, 1024> block{};
        const uint64_t frames = static_cast<uint64_t>(rate) * 600;
        uint64_t rendered = 0;
        while (rendered < frames) {
            const int count = static_cast<int>(std::min<uint64_t>(frames - rendered,
                rendered % 2 ? 511 : 193));
            block.fill(0);
            metronome.mix(block.data(), count, true, bpm, 80);
            rendered += count;
            const uint64_t beat = (rendered - 1) * bpm / (rate * 60ULL);
            check(metronome.beats() == beat + 1, "beat missing or duplicated");
            check(metronome.lastBeatFrame() == (beat * rate * 60 + bpm - 1) / bpm,
                  "beat timing drift exceeds sample quantization");
        }
    }
    // The generated audio must be independent of callback buffer boundaries.
    Metronome one, chunks; one.prepare(48000); chunks.prepare(48000);
    std::vector<float> entire(96000), split(96000);
    one.mix(entire.data(), 48000, true, 137, 100);
    for (int frame = 0; frame < 48000;) {
        const int count = std::min(127, 48000 - frame);
        chunks.mix(split.data() + frame * 2, count, true, 137, 100); frame += count;
    }
    check(entire == split, "audio depends on buffer size");
    check(std::any_of(entire.begin(), entire.end(), [](float x) { return std::abs(x) > .001f; }), "click is silent");

    Metronome tempo; tempo.prepare(48000);
    std::vector<float> buffer(48000, 0);
    tempo.mix(buffer.data(), 12000, true, 120, 50);
    tempo.mix(buffer.data(), 24000, true, 60, 50);
    check(tempo.beats() == 1, "tempo change retriggered the click");
    tempo.mix(buffer.data(), 1, true, 60, 50);
    check(tempo.beats() == 2 && tempo.lastBeatFrame() == 36000, "tempo change lost beat phase");
    std::fill(buffer.begin(), buffer.end(), 0);
    tempo.mix(buffer.data(), 24000, false, 60, 50);
    check(tempo.beats() == 2, "disabled metronome still schedules beats");
    check(std::all_of(buffer.begin() + 960, buffer.end(), [](float x) { return x == 0; }), "stop leaves a stuck click");
    tempo.mix(buffer.data(), 1, true, 60, 50);
    check(tempo.beats() == 3, "restart has no immediate click");

    for (float piano : {-1.0f, 1.0f}) {
        Metronome mix; mix.prepare(48000);
        std::fill(buffer.begin(), buffer.end(), piano);
        mix.mix(buffer.data(), 24000, true, 240, 100);
        for (float value : buffer) check(std::isfinite(value) && std::abs(value) <= 1,
                                       "piano plus click clips");
        std::fill(buffer.begin(), buffer.end(), piano);
        mix.mix(buffer.data(), 24000, false, 240, 100);
        check(buffer.back() == piano, "disabled metronome changes piano level");
    }
    Metronome mute; mute.prepare(48000);
    std::fill(buffer.begin(), buffer.end(), .3f);
    mute.mix(buffer.data(), 24000, true, 100, 0);
    check(std::all_of(buffer.begin(), buffer.end(), [](float x) { return x == .3f; }), "zero click volume changes piano");
    std::cout << "PASS: ten-minute beat timing, 3 sample rates, buffer boundaries, tempo changes, stop/restart, mix headroom, mute\n";
}
