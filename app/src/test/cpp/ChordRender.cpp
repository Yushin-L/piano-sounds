// Renders accompaniment scenarios through the real PianoSynth and reports how often the
// output stage saturates. Build on the host with -Iapp/src/test/cpp/host (see README).
//   ChordRender [wav-output-dir] [volume%]
#include "Limiter.h"
#include "PianoSynth.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

namespace {
constexpr int kRate = 48000, kBlock = 192;

struct Event { double time; int status, a, b; };
struct Scenario { const char* name; double seconds; std::vector<Event> events; };

void chord(std::vector<Event>& e, double on, double off, std::initializer_list<int> notes, int velocity) {
    int spread = 0;
    for (int note : notes) {
        // Human hands land a few milliseconds apart; exact unison would overstate peaks.
        e.push_back({on + 0.004 * spread++, 0x90, note, velocity});
        e.push_back({off, 0x80, note, 0});
    }
}

std::vector<Scenario> scenarios() {
    std::vector<Scenario> list;
    { Scenario s{"single", 3, {}}; chord(s.events, 0.1, 2.5, {60}, 100); list.push_back(s); }
    { Scenario s{"chord4", 3, {}}; chord(s.events, 0.1, 2.5, {48, 55, 60, 64}, 100); list.push_back(s); }
    { Scenario s{"chord6", 3, {}}; chord(s.events, 0.1, 2.5, {36, 43, 48, 52, 55, 60}, 100); list.push_back(s); }
    { Scenario s{"chord6_ff", 3, {}}; chord(s.events, 0.1, 2.5, {36, 43, 48, 52, 55, 60}, 127); list.push_back(s); }
    // C - Am - F - G, six notes each, legato changes every half second.
    const std::initializer_list<int> progression[] = {
        {36, 43, 48, 52, 55, 60}, {45, 52, 57, 60, 64, 69}, {41, 48, 53, 57, 60, 65}, {43, 50, 55, 59, 62, 67}};
    { Scenario s{"changes", 4.5, {}};
      for (int i = 0; i < 8; ++i) chord(s.events, 0.1 + i * 0.5, 0.58 + i * 0.5, progression[i % 4], 90 + (i % 3) * 10);
      list.push_back(s); }
    // Legato pedal: lift on each change, press again 80ms later.
    { Scenario s{"changes_pedal", 5, {}};
      for (int i = 0; i < 8; ++i) {
          const double t = 0.1 + i * 0.5;
          s.events.push_back({t, 0xb0, 64, 0});
          chord(s.events, t, t + 0.3, progression[i % 4], 90 + (i % 3) * 10);
          s.events.push_back({t + 0.08, 0xb0, 64, 127});
      }
      s.events.push_back({4.3, 0xb0, 64, 0});
      list.push_back(s); }
    // Worst case: pedal held through every change.
    { Scenario s{"pedal_wash", 5, {}};
      s.events.push_back({0.0, 0xb0, 64, 127});
      for (int i = 0; i < 8; ++i) chord(s.events, 0.1 + i * 0.5, 0.4 + i * 0.5, progression[i % 4], 90 + (i % 3) * 10);
      s.events.push_back({4.3, 0xb0, 64, 0});
      list.push_back(s); }
    for (auto& s : list) std::stable_sort(s.events.begin(), s.events.end(),
                                          [](const Event& a, const Event& b) { return a.time < b.time; });
    return list;
}

std::vector<float> render(PianoSynth& synth, const Scenario& s, float volume, int* maxVoices = nullptr) {
    synth.panic();
    std::vector<float> warm(kBlock * 2);
    // Let the smoothed master volume settle before the scenario starts.
    for (int i = 0; i < kRate / kBlock; ++i) synth.render(warm.data(), kBlock, volume);
    const int frames = static_cast<int>(s.seconds * kRate) / kBlock * kBlock;
    std::vector<float> out(frames * 2);
    size_t next = 0;
    for (int f = 0; f < frames; f += kBlock) {
        while (next < s.events.size() && s.events[next].time * kRate < f + kBlock) {
            synth.midi(s.events[next].status, s.events[next].a, s.events[next].b); ++next;
        }
        synth.render(out.data() + f * 2, kBlock, volume);
        if (maxVoices) *maxVoices = std::max(*maxVoices, synth.activeVoices());
    }
    return out;
}

void writeWav(const char* dir, const char* name, const std::vector<float>& data) {
    char path[4096];
    std::snprintf(path, sizeof(path), "%s/%s.wav", dir, name);
    std::FILE* wav = std::fopen(path, "wb");
    if (!wav) { std::fprintf(stderr, "cannot write %s\n", path); return; }
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, wav); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, wav); };
    const uint32_t bytes = static_cast<uint32_t>(data.size() * 2);
    std::fwrite("RIFF", 4, 1, wav); u32(36 + bytes); std::fwrite("WAVEfmt ", 8, 1, wav); u32(16);
    u16(1); u16(2); u32(kRate); u32(kRate * 4); u16(4); u16(16); std::fwrite("data", 4, 1, wav); u32(bytes);
    for (float v : data) u16(static_cast<uint16_t>(static_cast<int16_t>(std::lround(std::clamp(v, -1.0f, 1.0f) * 32767))));
    std::fclose(wav);
}

double db(double x) { return 20 * std::log10(std::max(x, 1e-9)); }

// Candidate master stages applied offline to the same pre-master mix.
struct Stage { const char* name; float trim; bool limit; };
constexpr Stage kStages[] = {
    {"saturate", 1.0f, false},     // Shipping behaviour up to 1.1.1.
    {"limit", 1.0f, true},         // Look-ahead limiter with PianoSynth's defaults.
};
constexpr int kShipping = 1;

std::vector<float> master(const Stage& stage, const std::vector<float>& mix, int* latency) {
    std::vector<float> out(mix.size());
    for (size_t i = 0; i < mix.size(); ++i) out[i] = mix[i] * stage.trim;
    *latency = 0;
    if (stage.limit) {
        Limiter limiter; limiter.prepare(kRate);
        limiter.process(out.data(), static_cast<int>(out.size() / 2));
        *latency = limiter.latency();
    } else {
        for (float& x : out) {
            const float magnitude = std::abs(x);
            if (magnitude > 0.8f) x = std::copysign(0.8f + 0.2f * (1.0f - std::exp(-(magnitude - 0.8f) * 5.0f)), x);
        }
    }
    return out;
}

// Energy left after removing a per-5ms best-fit gain: waveform distortion, not level changes.
double distortion(const std::vector<float>& out, const std::vector<float>& mix, int latency) {
    double error = 0, energy = 0;
    const size_t block = kRate / 200 * 2;
    for (size_t start = latency * 2; start + block <= out.size(); start += block) {
        double xy = 0, xx = 0, yy = 0;
        for (size_t i = start; i < start + block; ++i) {
            const double x = mix[i - latency * 2], y = out[i];
            xy += x * y; xx += x * x; yy += y * y;
        }
        error += xx > 0 ? yy - xy * xy / xx : yy;
        energy += yy;
    }
    return 10 * std::log10(std::max(error, 1e-18) / std::max(energy, 1e-18));
}
}  // namespace

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : "";
    const float volume = (argc > 2 ? std::atof(argv[2]) : 70) / 100.0f;
    PianoSynth synth;
    if (!synth.load(nullptr)) { std::fprintf(stderr, "failed to load grand.pno\n"); return 1; }
    synth.sampleRate(kRate);
    // A tiny master volume keeps the output stage linear, so scaling it back up recovers
    // the mix that reaches the master stage at the real volume.
    constexpr float probe = 0.001f;
    std::printf("volume %.0f%%\n%-14s %-10s %9s %8s %9s %9s %8s %8s %7s\n", volume * 100, "scenario", "stage",
                "pre-peak", ">0.8", "out-peak", "out-rms", "max-GR", "dist", "voices");
    double worst = 0;
    bool ok = true;
    for (const auto& s : scenarios()) {
        const auto probed = render(synth, s, probe);
        int voices = 0;
        const auto actual = render(synth, s, volume, &voices);
        std::vector<float> mix(probed.size());
        for (size_t i = 0; i < mix.size(); ++i) mix[i] = probed[i] * volume / probe;
        double prePeak = 0; size_t over = 0;
        for (float x : mix) { prePeak = std::max(prePeak, static_cast<double>(std::abs(x))); over += std::abs(x) > 0.8f; }
        for (const auto& stage : kStages) {
            int latency = 0;
            const auto out = master(stage, mix, &latency);
            double peak = 0, energy = 0, reduction = 0;
            for (size_t i = 0; i < out.size(); ++i) {
                peak = std::max(peak, static_cast<double>(std::abs(out[i])));
                energy += out[i] * out[i];
                if (i >= static_cast<size_t>(latency) * 2 && std::abs(mix[i - latency * 2]) > 1e-3f)
                    reduction = std::min(reduction, db(std::abs(out[i] / (mix[i - latency * 2] * stage.trim))));
            }
            std::printf("%-14s %-10s %6.1fdB %7.2f%% %6.1fdB %6.1fdB %5.1fdB %5.1fdB %7d\n", s.name, stage.name,
                        db(prePeak), 100.0 * over / mix.size(), db(peak), db(std::sqrt(energy / out.size())),
                        reduction, distortion(out, mix, latency), voices);
            char name[64];
            std::snprintf(name, sizeof(name), "%s-%s", s.name, stage.name);
            if (*dir) writeWav(dir, name, out);
            if (&stage == &kStages[kShipping]) {
                // The probe already passed through the synth's limiter once, so the offline
                // copy lags the real output by one extra look-ahead.
                for (size_t i = latency * 2; i < out.size(); ++i)
                    worst = std::max(worst, static_cast<double>(std::abs(out[i] - actual[i - latency * 2])));
                for (float v : actual) ok &= std::isfinite(v) && std::abs(v) <= 0.8901f;
                ok &= distortion(out, mix, latency) < -30;
            }
        }
    }
    ok &= worst < 1e-4;
    std::printf("max |offline limit - synth output| = %.2e\n%s\n", worst,
                ok ? "PASS: synth output below -1 dBFS, chord distortion < -30 dB" : "FAIL");
    return ok ? 0 : 1;
}
