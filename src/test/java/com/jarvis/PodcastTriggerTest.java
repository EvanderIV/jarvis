package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class PodcastTriggerTest {

    private static final String BATHROOM = TripwireListener.BATHROOM;
    private static final TripwireListener.Cause CROSSING = TripwireListener.Cause.CROSSING;

    private static class RecordingMusicManager extends MusicManager {
        final List<String> podcasts = new ArrayList<>();
        RecordingMusicManager(LmsController lms) { super(lms); }
        @Override public synchronized void playPodcast(String path, List<String> idleSpeakers) { podcasts.add(path); }
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-27T07:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private RecordingMusicManager music;
    private Path episode;
    private PodcastTrigger trigger;

    @Before
    public void setUp() throws Exception {
        LmsController lms = new LmsController("127.0.0.1");
        music = new RecordingMusicManager(lms);
        episode = Files.createTempDirectory("jarvis-podcast").resolve("latest.mp3");
        Files.createFile(episode);
        Files.setLastModifiedTime(episode, FileTime.fromMillis(1_000_000));
        trigger = new PodcastTrigger(music, lms, episode, clock);
    }

    private void walkIn() { trigger.onPresenceChanged(BATHROOM, true, CROSSING); }
    private void walkOut() { trigger.onPresenceChanged(BATHROOM, false, CROSSING); }
    private void wait(int seconds) { clock.now = clock.now.plus(Duration.ofSeconds(seconds)); }

    @Test
    public void playsOnWalkingOutAfterARealStay() {
        walkIn();
        wait(10 * 60);
        assertEquals("nothing while still inside", 0, music.podcasts.size());
        walkOut();
        assertEquals(1, music.podcasts.size());
    }

    @Test
    public void quickInAndOutBeforeTheShowerDoesntCount() {
        walkIn();
        wait(45);
        walkOut();          // stepped back out to finish undressing
        assertEquals(0, music.podcasts.size());
        wait(30);
        walkIn();
        wait(119);
        walkOut();          // the 2 minutes count from the latest walk-in
        assertEquals(0, music.podcasts.size());
        walkIn();
        wait(15 * 60);
        walkOut();
        assertEquals(1, music.podcasts.size());
    }

    @Test
    public void onlyRealCrossingsOfTheBathroomCount() {
        trigger.onPresenceChanged(BATHROOM, true, TripwireListener.Cause.OVERRIDE);
        wait(5 * 60);
        walkOut();
        walkIn();
        wait(5 * 60);
        trigger.onPresenceChanged(BATHROOM, false, TripwireListener.Cause.DAILY_RESET);
        walkOut();
        trigger.onPresenceChanged("kitchen_door", true, CROSSING);
        wait(5 * 60);
        trigger.onPresenceChanged("kitchen_door", false, CROSSING);
        assertEquals(0, music.podcasts.size());
    }

    @Test
    public void eachEpisodePlaysOnce() throws Exception {
        walkIn(); wait(5 * 60); walkOut();
        walkIn(); wait(5 * 60); walkOut();
        assertEquals("the second trip leaves the music alone", 1, music.podcasts.size());

        assertTrue("admin command plays it again", trigger.play(true));
        assertEquals(2, music.podcasts.size());

        Files.setLastModifiedTime(episode, FileTime.fromMillis(2_000_000)); // next day's upload
        walkIn(); wait(5 * 60); walkOut();
        assertEquals(3, music.podcasts.size());
        assertFalse(trigger.play(false));

        Files.delete(episode);
        assertFalse("no episode uploaded", trigger.play(true));
    }
}
