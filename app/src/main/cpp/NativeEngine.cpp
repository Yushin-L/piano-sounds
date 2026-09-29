#include "PianoSynth.h"
#include "EventQueue.h"
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <algorithm>
#include <cmath>

class Engine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    PianoSynth synth;
    EventQueue queue;
    std::shared_ptr<oboe::AudioStream> stream;
    std::atomic<bool> running{false}, silence{false}, disconnected{false};
    std::atomic<float> gain{0.7f};
    std::atomic<int> voices{0}, sampleRate{0}, burst{0}, buffer{0}, xruns{0}, drops{0};
    bool loaded = false;
    bool start() {
        stop();
        if (!loaded) return false;
        disconnected.store(false);
        xruns.store(0);
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(2)
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)->setErrorCallback(this);
        auto result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream);
        }
        if (result != oboe::Result::OK) return false;
        synth.sampleRate(stream->getSampleRate());
        sampleRate.store(stream->getSampleRate()); burst.store(stream->getFramesPerBurst());
        stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
        buffer.store(stream->getBufferSizeInFrames());
        running.store(true);
        result = stream->requestStart();
        if (result != oboe::Result::OK) { stop(); return false; }
        return true;
    }
    void stop() {
        running.store(false);
        if (stream) { stream->close(); stream.reset(); }
        queue.discard(); synth.panic(); voices.store(0);
        silence.store(false);
    }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audio, void* data, int32_t frames) override {
        if (silence.exchange(false)) { queue.discard(); synth.panic(); }
        MidiEvent event;
        // Bounded work even if input is continuously flooding.
        for (unsigned n = 0; n < EventQueue::capacity && queue.pop(event); ++n)
            synth.midi(event.status, event.a, event.b);
        synth.render(static_cast<float*>(data), frames, gain.load(std::memory_order_relaxed));
        voices.store(synth.activeVoices(), std::memory_order_relaxed);
        const auto count = audio->getXRunCount();
        if (count) xruns.store(count.value(), std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {
        running.store(false); disconnected.store(true);
    }
};

static Engine engine;
extern "C" {
JNIEXPORT jboolean JNICALL Java_com_pianosounds_app_NativeEngine_load(JNIEnv* env, jobject, jobject assets) {
    if (!engine.loaded) engine.loaded = engine.synth.load(AAssetManager_fromJava(env, assets));
    return engine.loaded;
}
JNIEXPORT jboolean JNICALL Java_com_pianosounds_app_NativeEngine_start(JNIEnv*, jobject) { return engine.start(); }
JNIEXPORT void JNICALL Java_com_pianosounds_app_NativeEngine_stop(JNIEnv*, jobject) { engine.stop(); }
JNIEXPORT void JNICALL Java_com_pianosounds_app_NativeEngine_midi(JNIEnv*, jobject, jint s, jint a, jint b) {
    if (!engine.running.load()) return;
    if (s < 0x80 || s > 255 || a < 0 || a > 127 || b < 0 || b > 127) return;
    if (!engine.queue.push({s, a, b})) { engine.drops.fetch_add(1); engine.silence.store(true); }
}
JNIEXPORT void JNICALL Java_com_pianosounds_app_NativeEngine_panic(JNIEnv*, jobject) {
    // Preserve ordering: notes played immediately after Stop must not be discarded.
    if (engine.running.load() && !engine.queue.push({0xff, 0, 0})) engine.silence.store(true);
}
JNIEXPORT void JNICALL Java_com_pianosounds_app_NativeEngine_volume(JNIEnv*, jobject, jfloat value) {
    engine.gain.store(std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.7f);
}
JNIEXPORT jintArray JNICALL Java_com_pianosounds_app_NativeEngine_stats(JNIEnv* env, jobject) {
    jint values[] = {engine.running.load(), engine.sampleRate.load(), engine.burst.load(), engine.buffer.load(),
                     engine.voices.load(), engine.xruns.load(), engine.drops.load(), engine.disconnected.load()};
    auto array = env->NewIntArray(8); env->SetIntArrayRegion(array, 0, 8, values); return array;
}
}
