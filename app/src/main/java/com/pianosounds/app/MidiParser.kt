package com.pianosounds.app

/** Stateful MIDI 1.0 stream decoder. A receiver call need not contain whole messages. */
class MidiParser(private val message: (status: Int, data1: Int, data2: Int) -> Unit) {
    private var status = 0
    private var needed = 0
    private var count = 0
    private var first = 0
    private var sysex = false

    fun reset() { status = 0; needed = 0; count = 0; sysex = false }

    fun feed(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        for (index in offset until offset + length) {
            val value = data[index].toInt() and 0xff
            if (value >= 0xf8) {
                if (value == 0xff) { reset(); message(0xff, 0, 0) }
                continue // real-time bytes may interrupt any other message
            }
            if (value and 0x80 != 0) {
                count = 0
                if (value >= 0xf0) {
                    status = 0 // system common cancels running status
                    needed = 0
                    sysex = value == 0xf0
                } else {
                    sysex = false
                    status = value
                    needed = if (value and 0xe0 == 0xc0) 1 else 2
                }
                continue
            }
            if (sysex || status == 0 || needed == 0) continue
            if (count == 0) first = value
            count++
            if (count == needed) {
                message(status, first, if (needed == 2) value else 0)
                count = 0
            }
        }
    }
}
