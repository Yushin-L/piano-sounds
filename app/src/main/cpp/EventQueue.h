#pragma once
#include <array>
#include <atomic>
#include <mutex>

struct MidiEvent { int status = 0, a = 0, b = 0; };

// Multiple non-realtime producers; the audio consumer never locks or allocates.
class EventQueue {
public:
    bool push(MidiEvent event) {
        std::lock_guard<std::mutex> lock(producers_);
        const auto write = write_.load(std::memory_order_relaxed);
        const auto next = (write + 1) % capacity;
        if (next == read_.load(std::memory_order_acquire)) return false;
        events_[write] = event;
        write_.store(next, std::memory_order_release);
        return true;
    }
    bool pop(MidiEvent& event) {
        const auto read = read_.load(std::memory_order_relaxed);
        if (read == write_.load(std::memory_order_acquire)) return false;
        event = events_[read];
        read_.store((read + 1) % capacity, std::memory_order_release);
        return true;
    }
    void discard() { read_.store(write_.load(std::memory_order_acquire), std::memory_order_release); }
    static constexpr unsigned capacity = 2048;
private:
    std::array<MidiEvent, capacity> events_{};
    std::atomic<unsigned> write_{0}, read_{0};
    std::mutex producers_;
};
