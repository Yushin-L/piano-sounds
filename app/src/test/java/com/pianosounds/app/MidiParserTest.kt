package com.pianosounds.app

import org.junit.Assert.*
import org.junit.Test

class MidiParserTest {
    private fun decode(vararg chunks: List<Int>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        val parser = MidiParser { s, a, b -> result += listOf(s, a, b) }
        chunks.forEach { parser.feed(it.map(Int::toByte).toByteArray(), 0, it.size) }
        return result
    }
    @Test fun splitPacketsAndRunningStatus() {
        assertEquals(listOf(listOf(0x90,60,100), listOf(0x90,64,70), listOf(0x80,60,0)),
            decode(listOf(0x90,60), listOf(100,64), listOf(70,0x80), listOf(60,0)))
    }
    @Test fun realtimeCanInterruptMessagesAndSysex() {
        assertEquals(listOf(listOf(0x90,60,100), listOf(0x91,64,90)),
            decode(listOf(0x90,60,0xf8,100,0xf0,1,0xfe,2,0xf7,3,0x91,64,90)))
    }
    @Test fun singleByteMessagesDoNotConsumeNextStatus() {
        assertEquals(listOf(listOf(0xc0,5,0), listOf(0xc0,6,0), listOf(0xd1,30,0), listOf(0x90,60,0)),
            decode(listOf(0xc0,5,6,0xd1,30,0x90,60,0)))
    }
    @Test fun systemCommonCancelsRunningStatus() {
        assertEquals(listOf(listOf(0x90,60,100), listOf(0xb0,64,127)),
            decode(listOf(0x90,60,100,0xf2,1,2,64,80,0xb0,64,127)))
    }
    @Test fun statusResynchronizesTruncatedMessage() {
        assertEquals(listOf(listOf(0x80,60,0)), decode(listOf(4,5,0x90,60,0x80,60,0)))
    }
    @Test fun resetSilencesAndClearsRunningStatus() {
        assertEquals(listOf(listOf(0xff,0,0), listOf(0x90,62,100)),
            decode(listOf(0x90,60,0xff,100,0x90,62,100)))
    }
    @Test fun allPacketBoundariesAreEquivalent() {
        val bytes = listOf(0x90,60,100,64,110,0xf8,0xb0,64,127,0x80,60,0,64,0,0xb0,64,0)
        val expected = decode(bytes)
        for (cut in 0..bytes.size) assertEquals(expected, decode(bytes.take(cut), bytes.drop(cut)))
        assertEquals(expected, decode(*bytes.map { listOf(it) }.toTypedArray()))
    }
    @Test fun honorsOffsetAndReset() {
        val result = mutableListOf<List<Int>>()
        val parser = MidiParser { s,a,b -> result += listOf(s,a,b) }
        val bytes = byteArrayOf(9,0x90.toByte(),60,100,9)
        parser.feed(bytes,1,3); parser.reset(); parser.feed(byteArrayOf(64,90),0,2)
        assertEquals(listOf(listOf(0x90,60,100)), result)
    }
}
