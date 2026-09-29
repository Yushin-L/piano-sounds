package com.pianosounds.app

import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PianoIntegrationTest {
    private fun MidiReceiver.send(bytes: ByteArray) = send(bytes, 0, bytes.size)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val outputDir: File get() {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (if (directory != null) File(directory) else context.filesDir).apply { mkdirs() }
    }
    private fun until(message: String, seconds: Int = 45, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (!condition()) {
            if (System.nanoTime() > deadline) fail(message)
            Thread.sleep(100)
        }
    }
    @Test fun sampleBankAndNativeAudioBehavior() {
        val result = NativeEngine.selfTest(context.assets, File(outputDir, "piano-demo.wav").absolutePath)
        File(outputDir, "native-checks.txt").writeText(result)
        assertTrue(result, result.startsWith("PASS:"))
    }

    @Test fun metronomeControlsAndLifecycle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            until("Metronome UI not ready") {
                var ready = false
                scenario.onActivity { ready = it.findViewById<Switch>(R.id.metronome_toggle).isEnabled }
                ready
            }
            assertEquals(0, NativeEngine.stats()[8])
            scenario.onActivity {
                it.findViewById<SeekBar>(R.id.metronome_bpm).progress = 97 // 137 BPM
                it.findViewById<SeekBar>(R.id.metronome_volume).progress = 65
                it.findViewById<Switch>(R.id.metronome_toggle).isChecked = true
            }
            until("Metronome did not produce beats") { NativeEngine.stats()[9] >= 2 }
            assertEquals(1, NativeEngine.stats()[8])
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                (content.getChildAt(0) as ScrollView).fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(outputDir, "metronome.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // Piano notes coexist with the click and survive the metronome switch.
            for (note in listOf(48, 55, 60, 64, 67, 72)) NativeEngine.midi(0x90, note, 100)
            until("Chord not mixed with metronome") { NativeEngine.stats()[4] >= 6 }
            scenario.onActivity { it.findViewById<Switch>(R.id.metronome_toggle).isChecked = false }
            assertEquals(0, NativeEngine.stats()[8])
            assertTrue(NativeEngine.stats()[4] >= 6)
            scenario.onActivity { it.findViewById<Switch>(R.id.metronome_toggle).isChecked = true }
            scenario.onActivity { it.findViewById<Button>(R.id.panic).performClick() }
            until("Stop all left a sound running") { NativeEngine.stats()[4] == 0 && NativeEngine.stats()[8] == 0 }
            scenario.onActivity { it.findViewById<Switch>(R.id.metronome_toggle).isChecked = true }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            until("Metronome stream still running in background") { NativeEngine.stats()[0] == 0 }
            assertEquals(0, NativeEngine.stats()[8])
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            until("Audio did not resume") { NativeEngine.stats()[0] == 1 }
            scenario.recreate()
            until("Audio did not restart after recreation") { NativeEngine.stats()[0] == 1 }
            scenario.onActivity {
                assertFalse(it.findViewById<Switch>(R.id.metronome_toggle).isChecked)
                assertEquals(97, it.findViewById<SeekBar>(R.id.metronome_bpm).progress)
                assertEquals(65, it.findViewById<SeekBar>(R.id.metronome_volume).progress)
            }
            assertEquals(0, NativeEngine.stats()[8])
        }
    }

    @Suppress("DEPRECATION")
    @Test fun appReceivesRealAndroidMidiAndRecoversAcrossLifecycle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            until("Audio stream did not start") { NativeEngine.stats()[0] == 1 }
            val manager = context.getSystemService(MidiManager::class.java)
            until("Test MIDI service was not discovered") {
                manager.devices.any { it.properties.getString("product") == "KeyLab Test Loopback" }
            }
            val info = manager.devices.first { it.properties.getString("product") == "KeyLab Test Loopback" }
            val latch = CountDownLatch(1)
            var device: MidiDevice? = null
            manager.openDevice(info, { device = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(latch.await(15, TimeUnit.SECONDS)); assertNotNull(device)
            device!!.use { opened ->
                opened.openInputPort(0).use { input ->
                    assertNotNull(input)
                    until("App did not open test MIDI output") {
                        var connected = false
                        scenario.onActivity { connected = it.findViewById<TextView>(R.id.connection_status).text.contains("●") }
                        connected
                    }
                    input.send(byteArrayOf(0xb0.toByte(), 64, 127, 0x90.toByte(), 60, 100))
                    until("MIDI note did not reach audio engine") { NativeEngine.stats()[4] > 0 }
                    // A USB keyboard may flush its packet after sending it. Flush is a
                    // delivery boundary, not a disconnect, and must not silence held notes.
                    input.flush()
                    Thread.sleep(300)
                    assertTrue("MIDI flush silenced a held note", NativeEngine.stats()[4] > 0)
                    input.send(byteArrayOf(0x80.toByte(), 60, 0))
                    Thread.sleep(500)
                    assertTrue("Sustain lost held note", NativeEngine.stats()[4] > 0)
                    var status = ""
                    scenario.onActivity { status = it.findViewById<TextView>(R.id.input_status).text.toString() }
                    assertTrue("Input counter not updated: $status", status.contains("입력 1회"))
                    // ATD disables display rendering; draw the real measured Android view tree.
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(outputDir, "midi-connected.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    input.send(byteArrayOf(0xb0.toByte(), 64, 0))
                    until("Pedal release stuck") { NativeEngine.stats()[4] == 0 }
                    input.send(byteArrayOf(0x90.toByte(), 64, 100))
                    until("Second note not received") { NativeEngine.stats()[4] > 0 }
                    input.flush()
                    input.send(byteArrayOf(67, 110)) // Running status survives a flush.
                    until("Running-status note lost after MIDI flush") { NativeEngine.stats()[4] > 1 }
                    scenario.onActivity { it.findViewById<Button>(R.id.panic).performClick() }
                    until("Panic button failed") { NativeEngine.stats()[4] == 0 }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    until("Audio not closed in background") { NativeEngine.stats()[0] == 0 }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    until("Audio not restored on resume") { NativeEngine.stats()[0] == 1 }
                    scenario.recreate()
                    until("Audio not restored after recreation") { NativeEngine.stats()[0] == 1 }
                    until("Port not restored after recreation") {
                        var connected = false
                        scenario.onActivity { connected = it.findViewById<TextView>(R.id.connection_status).text.contains("●") }
                        connected
                    }
                    input.send(byteArrayOf(0x90.toByte(), 67, 110))
                    until("MIDI not reconnected after recreation") { NativeEngine.stats()[4] > 0 }
                    scenario.onActivity { it.findViewById<Button>(R.id.panic).performClick() }
                    until("Panic after recreation failed") { NativeEngine.stats()[4] == 0 }
                }
            }
        }
        until("Stream leaked after closing Activity") { NativeEngine.stats()[0] == 0 }
    }
}
