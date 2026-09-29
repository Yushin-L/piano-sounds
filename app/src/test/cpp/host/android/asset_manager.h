#pragma once
// Host-only stand-in for the NDK asset API so PianoSynth can be tested off-device.
// Asset names resolve relative to PIANO_ASSET_DIR (default: app/src/main/assets).
#include <cstdint>
#include <cstdio>
#include <cstdlib>

struct AAssetManager {};
struct AAsset { std::FILE* file; int64_t remaining; };
enum { AASSET_MODE_STREAMING = 2 };

inline AAsset* AAssetManager_open(AAssetManager*, const char* name, int) {
    const char* dir = std::getenv("PIANO_ASSET_DIR");
    char path[4096];
    std::snprintf(path, sizeof(path), "%s/%s", dir ? dir : "app/src/main/assets", name);
    std::FILE* file = std::fopen(path, "rb");
    if (!file) return nullptr;
    std::fseek(file, 0, SEEK_END);
    const int64_t size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    return new AAsset{file, size};
}
inline int AAsset_read(AAsset* asset, void* buffer, size_t count) {
    const size_t n = std::fread(buffer, 1, count, asset->file);
    asset->remaining -= static_cast<int64_t>(n);
    return static_cast<int>(n);
}
inline int64_t AAsset_getRemainingLength64(AAsset* asset) { return asset->remaining; }
inline void AAsset_close(AAsset* asset) { std::fclose(asset->file); delete asset; }
