package com.pianosounds.app;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import java.io.IOException;

/** Runs in the separate test APK process, without the target app's Kotlin runtime. */
public final class TestMidiService extends MidiDeviceService {
    @Override public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] { new MidiReceiver() {
            @Override public void onSend(byte[] data, int offset, int count, long timestamp) throws IOException {
                for (MidiReceiver output : getOutputPortReceivers()) {
                    output.send(data, offset, count, timestamp);
                }
            }
            @Override public void onFlush() throws IOException {
                for (MidiReceiver output : getOutputPortReceivers()) {
                    output.flush();
                }
            }
        } };
    }
}
