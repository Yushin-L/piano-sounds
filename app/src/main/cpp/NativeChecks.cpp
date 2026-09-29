#include "PianoSynth.h"
#include "EventQueue.h"
#include <android/asset_manager_jni.h>
#include <jni.h>
#include <cmath>
#include <fstream>
#include <stdexcept>
#include <string>
#include <thread>

static void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
static double energy(const std::vector<float>& signal) {
    double sum = 0;
    for (float value : signal) {
        check(std::isfinite(value) && std::abs(value) <= 1.00001f, "nonfinite or clipped audio");
        sum += value * value;
    }
    return sum / signal.size();
}
extern "C" JNIEXPORT jstring JNICALL Java_com_pianosounds_app_NativeEngine_selfTest(
        JNIEnv* env, jobject, jobject assets, jstring wavPath) {
    try {
        PianoSynth synth;
        check(synth.load(AAssetManager_fromJava(env, assets)), "sample bank failed to load");
        check(synth.sampleCount() == 90, "missing samples");
        std::vector<float> block(8192);
        synth.render(block.data(), 4096, .7f);
        check(energy(block) == 0, "idle must be silent");
        for (int note = 21; note <= 108; ++note) {
            synth.panic(); synth.midi(0x90, note, 100); synth.render(block.data(), 4096, .7f);
            check(energy(block) > 1e-10, "silent note in 88-note range");
        }
        synth.panic(); synth.midi(0x90, 60, 20); synth.render(block.data(), 4096, .7f);
        const double soft = energy(block);
        synth.panic(); synth.midi(0x90, 60, 127); synth.render(block.data(), 4096, .7f);
        check(energy(block) > soft * 2, "velocity dynamics missing");
        synth.midi(0x90, 60, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 0, "velocity-zero note on must release");
        synth.midi(0xb0, 64, 127); synth.midi(0x90, 60, 100); synth.midi(0x80, 60, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 1 && energy(block) > 1e-10, "sustain should hold released key");
        synth.midi(0xb1, 64, 0);
        check(synth.sustained(0), "pedal leaked across channels");
        synth.midi(0xb0, 64, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 0, "pedal release stuck");
        synth.midi(0xb0, 64, 127); synth.midi(0x90, 60, 90); synth.midi(0x80, 60, 0);
        synth.midi(0xb0, 121, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(!synth.sustained(0) && synth.activeVoices() == 0, "reset controllers stuck");
        synth.midi(0x90, 60, 90); synth.midi(0x91, 64, 90); synth.midi(0xb0, 120, 0);
        check(synth.activeVoices() == 1, "all sound off must be channel-local");
        synth.midi(0xb1, 123, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 0, "all notes off failed");
        synth.midi(0xb0, 64, 127); synth.midi(0x90, 60, 90); synth.midi(0xb0, 123, 0);
        synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 1, "all notes off must respect sustain");
        synth.panic(); synth.midi(0x90, 60, 90); synth.midi(0x90, 60, 100); synth.midi(0x80, 60, 0);
        for (int i = 0; i < 5; ++i) synth.render(block.data(), 4096, .7f);
        check(synth.activeVoices() == 0, "repeated note stuck");
        for (int i = 0; i < 150; ++i) synth.midi(0x90 + i / 88, 21 + i % 88, 127);
        check(synth.activeVoices() == PianoSynth::kVoices, "voice limit failed");
        synth.render(block.data(), 4096, 1); energy(block);
        for (float value : block) check(std::abs(value) <= 0.8901f, "limiter let peaks past -1 dBFS");
        synth.midi(0xff, 0, 0); synth.render(block.data(), 4096, 1);
        check(synth.activeVoices() == 0 && energy(block) == 0, "panic must silence everything");
        for (int rate : {44100, 48000, 96000}) {
            synth.sampleRate(rate); synth.midi(0x90, 69, 100); synth.render(block.data(), 4096, .7f);
            check(energy(block) > 1e-10, "sample-rate adaptation failed"); synth.panic();
        }
        synth.sampleRate(48000); synth.midi(0x90, 60, 100);
        for (int i = 0; i < 8; ++i) synth.render(block.data(), 4096, 0);
        check(energy(block) == 0, "volume zero must become exactly silent");
        synth.render(block.data(), 4096, .7f);
        check(energy(block) > 1e-10, "volume restore failed"); synth.panic();
        EventQueue queue;
        for (unsigned i = 0; i < EventQueue::capacity - 1; ++i) check(queue.push({0x90, static_cast<int>(i), 1}), "queue capacity wrong");
        check(!queue.push({}), "overflow not detected");
        MidiEvent e;
        for (unsigned i = 0; i < EventQueue::capacity - 1; ++i) { check(queue.pop(e), "event missing"); check(e.a == static_cast<int>(i), "queue reordered events"); }
        check(!queue.pop(e), "queue not empty");
        std::thread first([&] { for (int i = 0; i < 10000; ++i) while (!queue.push({1, i, 0})) std::this_thread::yield(); });
        std::thread second([&] { for (int i = 0; i < 10000; ++i) while (!queue.push({2, i, 0})) std::this_thread::yield(); });
        int next[2] = {0, 0}; bool ordered = true;
        for (int i = 0; i < 20000;) if (queue.pop(e)) { ordered &= e.a == next[e.status - 1]++; ++i; }
        first.join(); second.join(); check(ordered && next[0] == 10000 && next[1] == 10000, "concurrent producers corrupted events");
        // Export actual engine output as a reviewable stereo WAV, not a mocked waveform.
        synth.sampleRate(48000); synth.panic();
        const int frames = 48000 * 6;
        std::vector<float> demo(frames * 2);
        for (int f = 0; f < frames; f += 240) {
            if (f % 24000 == 0 && f < 192000) synth.midi(0x90, 60 + (f / 24000) * 2, 45 + (f / 24000) * 10);
            if (f % 24000 == 18000 && f < 192000) synth.midi(0x80, 60 + (f / 24000) * 2, 0);
            if (f == 192000) { synth.midi(0xb0, 64, 127); for (int n : {48, 60, 64, 67, 72}) synth.midi(0x90, n, 95); }
            if (f == 240000) { synth.midi(0xb0, 64, 0); synth.midi(0xb0, 123, 0); }
            synth.render(demo.data() + f * 2, 240, .7f);
        }
        check(energy(demo) > 1e-8, "demo silent");
        const char* path = env->GetStringUTFChars(wavPath, nullptr);
        std::ofstream wav(path, std::ios::binary);
        env->ReleaseStringUTFChars(wavPath, path);
        auto u32 = [&](uint32_t v) { wav.write(reinterpret_cast<char*>(&v), 4); };
        auto u16 = [&](uint16_t v) { wav.write(reinterpret_cast<char*>(&v), 2); };
        wav.write("RIFF", 4); u32(36 + frames * 4); wav.write("WAVEfmt ", 8); u32(16);
        u16(1); u16(2); u32(48000); u32(48000 * 4); u16(4); u16(16); wav.write("data", 4); u32(frames * 4);
        for (float v : demo) u16(static_cast<int16_t>(v * 32767));
        check(wav.good(), "demo write failed");
        return env->NewStringUTF("PASS: 90 samples, 88 notes, velocity, sustain, channels, CC reset, retrigger, 96 voices, limiting, rates, concurrent queue, stereo WAV");
    } catch (const std::exception& error) {
        return env->NewStringUTF((std::string("FAIL: ") + error.what()).c_str());
    }
}
