package com.jarvis;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RoutineEngineTest {

    /** Records theme switches instead of talking to LMS. */
    private static class RecordingMusicManager extends MusicManager {
        final List<String> themes = Collections.synchronizedList(new ArrayList<>());
        volatile boolean playing = false;

        RecordingMusicManager(LmsController lms) { super(lms); }

        @Override
        public synchronized void switchTheme(String newTheme, List<String> targetMacs, int minSpeed, int maxSpeed) {
            themes.add(newTheme);
            playing = true;
        }

        @Override
        public synchronized void stopMusic() { playing = false; }

        @Override
        public boolean isPlaying() { return playing; }
    }

    private RecordingMusicManager music;
    private RoutineEngine engine;
    private final List<String> noSpeakers = new ArrayList<>();

    @Before
    public void setUp() {
        LmsController lms = new LmsController("127.0.0.1");
        music = new RecordingMusicManager(lms);
        engine = new RoutineEngine(lms, music);
    }

    @Test
    public void killTheMusicStopsPendingThemeChanges() throws Exception {
        // A morning-style routine: a theme now, intensity changes later
        engine.createRoutine(noSpeakers)
            .playTheme(new String[] { "wakeup" })
            .waitSeconds(1)
            .playTheme(new String[] { "relaxing" })
            .waitSeconds(1)
            .playTheme(new String[] { "upbeat" })
            .triggerNow();
        Thread.sleep(300);
        assertEquals(List.of("wakeup"), music.themes);

        // What CommandFulfiller does for "kill the music"
        engine.cancelActiveRoutines();
        music.stopMusic();

        Thread.sleep(2500);
        assertEquals("no theme change may restart the music after it was killed", List.of("wakeup"), music.themes);
    }

    @Test
    public void stepsDueTogetherRunInDefinedOrder() throws Exception {
        engine.createRoutine(noSpeakers)
            .playTheme(new String[] { "a" })
            .playTheme(new String[] { "b" })
            .playTheme(new String[] { "c" })
            .playTheme(new String[] { "d" })
            .triggerNow();
        Thread.sleep(500);
        assertEquals(List.of("a", "b", "c", "d"), music.themes);
    }

    @Test
    public void cancelLeavesLaterRoutinesWorking() throws Exception {
        engine.createRoutine(noSpeakers).waitSeconds(1).playTheme(new String[] { "first" }).triggerNow();
        engine.cancelActiveRoutines();
        engine.createRoutine(noSpeakers).playTheme(new String[] { "second" }).triggerNow();
        Thread.sleep(1500);
        assertEquals(List.of("second"), music.themes);
    }
}
