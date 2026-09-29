package com.pianosounds.app

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import java.io.IOException

/** Connection ownership lives on the main thread. MidiReceiver runs off the UI thread. */
class MidiConnection(
    context: Context,
    private val state: (String, Boolean) -> Unit,
    private val received: (Int, Int, Int) -> Unit,
    private val disconnected: () -> Unit,
) {
    data class Port(val device: MidiDeviceInfo, val number: Int, val name: String) {
        val key get() = "${device.id}:$number"
    }
    private val manager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var selected: Port? = null
    private var opening = false
    var available: List<Port> = emptyList(); private set

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            // A newly plugged-in keyboard takes precedence over an existing virtual source.
            if (info.type == MidiDeviceInfo.TYPE_USB && selected?.device?.type == MidiDeviceInfo.TYPE_VIRTUAL) closePort()
            refresh()
        }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (selected?.device?.id == info.id) { closePort(); state("건반 연결이 해제되었습니다", false) }
            refresh()
        }
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
            val target = selected
            if (active && target?.device?.id == status.deviceInfo.id && device == null && !opening) connect(target)
        }
    }

    fun start() {
        if (active) return
        active = true
        if (manager == null) { state("이 기기에서 MIDI를 사용할 수 없습니다", false); return }
        manager.registerDeviceCallback(callback, handler)
        refresh()
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        if (!active) return
        available = manager?.devices.orEmpty()
            .filter { it.type == MidiDeviceInfo.TYPE_USB || it.type == MidiDeviceInfo.TYPE_VIRTUAL }
            .flatMap { info ->
                val deviceName = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                    ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI 건반"
                info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.map {
                    Port(info, it.portNumber, "$deviceName · ${it.name.ifBlank { "포트 ${it.portNumber + 1}" }}")
                }
            }.sortedWith(compareBy<Port> { if (it.name.contains("keylab", true)) 0 else 1 }
                .thenBy { if (it.name.contains("DAW", true) || it.name.contains("MIDIIN2", true)) 1 else 0 }
                .thenBy { it.number })
        if (device == null && !opening) {
            if (available.isEmpty()) state("USB 건반을 연결해 주세요", false)
            else connect(available.first())
        }
    }

    fun connect(target: Port, attempt: Int = 0) {
        if (!active) return
        closePort()
        selected = target
        opening = true
        val token = generation
        state("${target.name}\n연결 중…", false)
        try {
            manager?.openDevice(target.device, { opened ->
                if (!active || token != generation) { closeDevice(opened); return@openDevice }
                opening = false
                if (opened == null) {
                    if (attempt < 4) {
                        state("장치를 준비하고 있습니다. 다시 연결 중…", false)
                        handler.postDelayed({
                            if (active && token == generation && device == null && !opening) connect(target, attempt + 1)
                        }, 500L shl attempt)
                    } else state("건반을 열 수 없습니다. 포트를 다시 선택해 주세요", false)
                    return@openDevice
                }
                try {
                    val output = opened.openOutputPort(target.number)
                    if (output == null) { closeDevice(opened); state("사용할 수 없는 MIDI 포트입니다", false); return@openDevice }
                    val parser = MidiParser { s, a, b -> if (active && token == generation) received(s, a, b) }
                    output.connect(object : MidiReceiver() {
                        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            synchronized(parser) { parser.feed(data, offset, count) }
                        }
                        // No MIDI messages are scheduled or buffered here. A flush must not
                        // close the device, stop held notes, or erase running status.
                        override fun onFlush() = Unit
                    })
                    device = opened; port = output
                    state(target.name, true)
                } catch (_: IOException) {
                    closeDevice(opened); state("연결에 실패했습니다. USB 케이블을 확인해 주세요", false)
                }
            }, handler)
        } catch (_: SecurityException) {
            opening = false; state("MIDI 장치 접근이 허용되지 않았습니다", false)
        } catch (_: IllegalArgumentException) {
            opening = false; state("장치가 해제되었습니다. 다시 연결해 주세요", false)
        }
    }

    private fun closeDevice(value: MidiDevice?) {
        try { value?.close() } catch (_: IOException) { }
    }

    private fun closePort() {
        generation++; opening = false
        try { port?.close() } catch (_: IOException) { }
        closeDevice(device)
        port = null; device = null; selected = null
        disconnected()
    }

    fun stop() {
        if (!active) return
        active = false; manager?.unregisterDeviceCallback(callback)
        closePort(); available = emptyList()
    }
}
