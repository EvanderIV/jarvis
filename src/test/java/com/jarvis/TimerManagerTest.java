package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.Test;

public class TimerManagerTest {

    @Test
    public void generatedChimeIsAValidWav() throws Exception {
        byte[] wav = TimerManager.buildChimeWav();
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            assertEquals(44100f, in.getFormat().getSampleRate(), 0.1f);
            assertEquals(16, in.getFormat().getSampleSizeInBits());
            double seconds = in.getFrameLength() / in.getFormat().getFrameRate();
            assertTrue("chime should be a few seconds long, was " + seconds, seconds > 2 && seconds < 5);
        }
    }
}
