package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class MusicManagerTest {

    @Test
    public void playsCompressedMp3WhenListedFileWasReplaced() throws Exception {
        Path dir = Files.createTempDirectory("jarvis-music");
        Files.createFile(dir.resolve("Song.mp3"));
        Files.createFile(dir.resolve("Other.flac"));
        String d = dir.toString();

        // music.json still says .flac, the server has the compressed .mp3
        assertEquals(dir.resolve("Song.mp3").toString(), MusicManager.resolvePlayablePath(d, "Song.flac"));
        // untouched files play as listed
        assertEquals(dir.resolve("Other.flac").toString(), MusicManager.resolvePlayablePath(d, "Other.flac"));
        // missing everywhere: return the listed path so LMS logs the real name
        assertEquals(dir.resolve("Gone.flac").toString(), MusicManager.resolvePlayablePath(d, "Gone.flac"));
    }

    // --- Podcast handoff ---

    private static final String PODCAST = "/pod/latest.mp3";
    private static final List<String> SPEAKERS = List.of("aa:aa");

    /** Records what would be sent to LMS; reports a configurable position in the current track. */
    private static class FakeLms extends LmsController {
        final List<String> played = Collections.synchronizedList(new ArrayList<>());
        volatile boolean unmuted = false;
        volatile double time = 0, duration = 200;

        FakeLms() { super("127.0.0.1"); }

        @Override public void playFile(List<String> targetMacs, String path) { played.add(path); }
        @Override public void setVolume(List<String> targetMacs, int volumePercent) { }
        @Override public void unmute(List<String> targetMacs) { unmuted = true; }
        @Override public void stop(List<String> targetMacs) { }
        @Override public List<String> getAllRegisteredSpeakers() { return SPEAKERS; }
        @Override public Map<String, Object> getPlaybackStatus(String playerId) {
            Map<String, Object> s = new HashMap<>();
            s.put("mode", "play");
            s.put("time", time);
            s.put("duration", duration);
            return s;
        }

        String last() { return played.isEmpty() ? null : played.get(played.size() - 1); }
    }

    private static MusicManager.Track track(String file, String genre) {
        MusicManager.Track t = new MusicManager.Track();
        t.file = file;
        t.title = file;
        t.genres = List.of(genre);
        t.moods = List.of();
        t.speed = 2;
        return t;
    }

    private static MusicManager musicWithLibrary(FakeLms lms) throws Exception {
        MusicManager music = new MusicManager(lms);
        Field library = MusicManager.class.getDeclaredField("library");
        library.setAccessible(true);
        library.set(music, new ArrayList<>(List.of(track("Morning.mp3", "first"), track("Later.mp3", "second"))));
        return music;
    }

    /** What the playback monitor does when LMS reports the current track (or podcast) ended. */
    private static void currentTrackEnds(MusicManager music) throws Exception {
        Method next = MusicManager.class.getDeclaredMethod("playNextTrack");
        next.setAccessible(true);
        next.invoke(music);
    }

    @Test
    public void podcastInterruptsSongThenResumesTheRoutinesCurrentTheme() throws Exception {
        FakeLms lms = new FakeLms();
        MusicManager music = musicWithLibrary(lms);
        music.playMusic("first", SPEAKERS);
        assertTrue(lms.last().endsWith("Morning.mp3"));

        lms.time = 100; // 100 s left: cut the song off
        music.playPodcast(PODCAST, SPEAKERS);
        assertEquals(PODCAST, lms.last());

        // The routine moves on to its next stage while the podcast plays
        music.switchTheme("second", SPEAKERS, 0, 4);
        assertEquals("theme switch must not interrupt the podcast", PODCAST, lms.last());

        currentTrackEnds(music);
        assertTrue(lms.last().endsWith("Later.mp3"));
        assertTrue(music.isPlaying());
        music.stopMusic();
    }

    @Test
    public void songNearItsEndFinishesBeforeThePodcast() throws Exception {
        FakeLms lms = new FakeLms();
        MusicManager music = musicWithLibrary(lms);
        music.playMusic("first", SPEAKERS);

        lms.time = 190; // 10 s left
        music.playPodcast(PODCAST, SPEAKERS);
        assertTrue("the song keeps playing", lms.last().endsWith("Morning.mp3"));

        currentTrackEnds(music);
        assertEquals(PODCAST, lms.last());
        currentTrackEnds(music);
        assertTrue(lms.last().endsWith(".mp3") && !PODCAST.equals(lms.last()));
        music.stopMusic();
    }

    @Test
    public void podcastFromSilenceStopsWhenDone() throws Exception {
        FakeLms lms = new FakeLms();
        MusicManager music = musicWithLibrary(lms);

        music.playPodcast(PODCAST, SPEAKERS);
        assertEquals(List.of(PODCAST), lms.played);
        assertTrue(lms.unmuted);

        currentTrackEnds(music);
        assertEquals("no music afterwards", List.of(PODCAST), lms.played);
        assertFalse(music.isPlaying());
    }

    @Test
    public void killingTheMusicDropsAQueuedPodcast() throws Exception {
        FakeLms lms = new FakeLms();
        MusicManager music = musicWithLibrary(lms);
        music.playMusic("first", SPEAKERS);
        lms.time = 195;
        music.playPodcast(PODCAST, SPEAKERS);

        music.stopMusic();
        currentTrackEnds(music);
        assertFalse(lms.played.contains(PODCAST));
        assertFalse(music.isPlaying());
    }
}
