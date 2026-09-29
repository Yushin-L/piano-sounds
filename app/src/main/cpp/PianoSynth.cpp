#include "PianoSynth.h"
#include <algorithm>
#include <cmath>
#include <cstring>

bool PianoSynth::load(AAssetManager* assets) {
    auto* asset = AAssetManager_open(assets, "grand.pno", AASSET_MODE_STREAMING);
    if (!asset) return false;
    auto read = [&](void* data, size_t size) {
        auto* bytes = static_cast<char*>(data);
        while (size > 0) {
            const int n = AAsset_read(asset, bytes, size);
            if (n <= 0) return false;
            size -= n; bytes += n;
        }
        return true;
    };
    char magic[4]; uint32_t count = 0;
    bool ok = read(magic, 4) && std::memcmp(magic, "PNO1", 4) == 0 && read(&count, 4) && count == 90;
    std::vector<PianoSample> bank;
    for (uint32_t i = 0; ok && i < count; ++i) {
        uint32_t h[4];
        ok = read(h, sizeof(h)) && h[0] == 21 + (i / 3) * 3 && h[1] == i % 3 &&
             h[2] >= 2 && h[2] <= 288000 && h[3] == 24000;
        if (!ok) break;
        PianoSample s;
        s.root = static_cast<int>(h[0]); s.layer = static_cast<int>(h[1]); s.rate = static_cast<int>(h[3]);
        s.pcm.resize(h[2] * 2);
        ok = read(s.pcm.data(), s.pcm.size() * sizeof(int16_t));
        bank.push_back(std::move(s));
    }
    ok = ok && AAsset_getRemainingLength64(asset) == 0;
    AAsset_close(asset);
    if (!ok) return false;
    panic(); samples_ = std::move(bank);
    return true;
}

void PianoSynth::release(Voice& v) {
    v.held = false;
    v.releasing = true;
    const float seconds = v.note >= 89 ? 1.5f : 0.22f;
    v.release = std::exp(std::log(0.0001f) / (seconds * rate_));
}

void PianoSynth::noteOn(int channel, int note, int velocity) {
    if (samples_.empty() || note < 21 || note > 108) return;
    // Repeated strikes retrigger a key while sustained tails can finish naturally.
    noteOff(channel, note);
    Voice* slot = nullptr;
    for (auto& v : voices_) if (!v.sample) { slot = &v; break; }
    if (!slot) {
        slot = &*std::min_element(voices_.begin(), voices_.end(), [](const Voice& a, const Voice& b) {
            if (a.releasing != b.releasing) return a.releasing;
            return a.age < b.age;
        });
    }
    const float oldL = slot->lastL, oldR = slot->lastR;
    const bool stealing = slot->sample != nullptr;
    *slot = Voice{};
    const int rootIndex = std::clamp((note - 21 + 1) / 3, 0, 29);
    const int layer = velocity < 54 ? 0 : velocity < 95 ? 1 : 2;
    slot->sample = &samples_[rootIndex * 3 + layer];
    slot->note = note; slot->channel = channel; slot->age = ++clock_; slot->held = true;
    slot->step = (static_cast<double>(slot->sample->rate) / rate_) * std::pow(2.0, (note - slot->sample->root) / 12.0);
    slot->gain = 0.35f + 0.65f * velocity / 127.0f;
    if (stealing) { slot->stolenL = oldL; slot->stolenR = oldR; slot->stolenFade = 1; }
}

void PianoSynth::noteOff(int channel, int note) {
    for (auto& v : voices_) {
        if (v.sample && v.channel == channel && v.note == note && v.held) {
            v.held = false;
            if (!sustain_[channel]) release(v);
        }
    }
}

void PianoSynth::midi(int status, int a, int b) {
    const int channel = status & 15;
    switch (status & 0xf0) {
        case 0x90: if (b > 0) noteOn(channel, a, b); else noteOff(channel, a); break;
        case 0x80: noteOff(channel, a); break;
        case 0xb0:
            if (a == 64 || a == 121) {
                sustain_[channel] = a == 64 && b >= 64;
                if (!sustain_[channel]) for (auto& v : voices_)
                    if (v.sample && v.channel == channel && !v.held) release(v);
            } else if (a == 120) {
                for (auto& v : voices_) if (v.channel == channel) v = Voice{};
                sustain_[channel] = false;
            } else if (a == 123) {
                for (auto& v : voices_) if (v.sample && v.channel == channel && v.held) {
                    v.held = false;
                    if (!sustain_[channel]) release(v);
                }
            }
            break;
        default: if (status == 0xff) panic(); break;
    }
}

void PianoSynth::panic() {
    for (auto& v : voices_) v = Voice{};
    limiter_.reset();
    sustain_.fill(false);
}

int PianoSynth::activeVoices() const {
    int n = 0; for (const auto& v : voices_) if (v.sample) ++n; return n;
}

void PianoSynth::render(float* output, int frames, float targetVolume) {
    std::fill(output, output + frames * 2, 0.0f);
    const float attackStep = 1.0f / (0.0005f * rate_);
    const float stealStep = 1.0f / (0.003f * rate_);
    for (auto& v : voices_) {
        if (!v.sample) continue;
        const auto& pcm = v.sample->pcm;
        const size_t sampleFrames = pcm.size() / 2;
        for (int frame = 0; frame < frames; ++frame) {
            const auto index = static_cast<size_t>(v.position);
            if (index + 1 >= sampleFrames || v.envelope < 0.0001f) { v = Voice{}; break; }
            const float fraction = static_cast<float>(v.position - index);
            v.attack = std::min(1.0f, v.attack + attackStep);
            const float amp = v.gain * v.envelope * v.attack / 32768.0f;
            const float l = (pcm[index * 2] + (pcm[index * 2 + 2] - pcm[index * 2]) * fraction) * amp;
            const float r = (pcm[index * 2 + 1] + (pcm[index * 2 + 3] - pcm[index * 2 + 1]) * fraction) * amp;
            v.lastL = l + v.stolenL * v.stolenFade;
            v.lastR = r + v.stolenR * v.stolenFade;
            output[frame * 2] += v.lastL;
            output[frame * 2 + 1] += v.lastR;
            v.stolenFade = std::max(0.0f, v.stolenFade - stealStep);
            v.position += v.step;
            if (v.releasing) v.envelope *= v.release;
        }
    }
    const float smoothing = 1.0f - std::exp(-1.0f / (rate_ * 0.01f));
    for (int i = 0; i < frames; ++i) {
        volume_ += (targetVolume - volume_) * smoothing;
        if (std::abs(volume_ - targetVolume) < 1e-7f) volume_ = targetVolume;
        for (int ch = 0; ch < 2; ++ch) output[i * 2 + ch] *= volume_ * 1.4f;
    }
    // Loud chords sum well past full scale; a gain limiter keeps attacks clean where
    // per-sample saturation used to distort them (#1).
    limiter_.process(output, frames);
}
