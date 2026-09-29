package com.pianosounds.app

import android.content.res.AssetManager

object NativeEngine {
    init { System.loadLibrary("piano") }
    external fun load(assets: AssetManager): Boolean
    external fun start(): Boolean
    external fun stop()
    external fun midi(status: Int, data1: Int, data2: Int)
    external fun panic()
    external fun volume(value: Float)
    external fun metronome(enabled: Boolean, bpm: Int, volume: Int)
    external fun stats(): IntArray
    // Offline checks use a separate synth, never the live audio stream.
    external fun selfTest(assets: AssetManager, wavPath: String): String
}
