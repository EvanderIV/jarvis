package com.jarvis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Plays the daily podcast when the user walks out of the bathroom (by then they're
 * more awake), but only after a stay of at least MIN_STAY — a quick in-and-out, like
 * stepping back out to finish undressing before the shower, doesn't count.
 * Each episode plays once: later bathroom trips (and the ones before the next upload)
 * leave the music alone. The admin "podcast" command plays it regardless.
 */
public class PodcastTrigger implements TripwireListener.PresenceListener {

    // Uploaded daily; the file is replaced in place, so its modified time identifies the episode
    private static final String PODCAST_FILE = "/home/evanm/Documents/jarvis-podcast/latest.mp3";
    private static final Duration MIN_STAY = Duration.ofMinutes(2);

    private final MusicManager musicManager;
    private final LmsController lmsController;
    private final Path podcast;
    private final Clock clock;
    private long playedEpisode = -1;
    private Instant walkedInAt = null;

    public PodcastTrigger(MusicManager musicManager, LmsController lmsController) {
        this(musicManager, lmsController, Paths.get(PODCAST_FILE), Clock.systemDefaultZone());
    }

    PodcastTrigger(MusicManager musicManager, LmsController lmsController, Path podcast, Clock clock) {
        this.musicManager = musicManager;
        this.lmsController = lmsController;
        this.podcast = podcast;
        this.clock = clock;
    }

    @Override
    public synchronized void onPresenceChanged(String node, boolean inside, TripwireListener.Cause cause) {
        if (!TripwireListener.BATHROOM.equals(node)) return;
        // Only real crossings count; a manual "room in/out" or the 4 AM reset shouldn't start audio
        if (cause != TripwireListener.Cause.CROSSING) {
            walkedInAt = null;
            return;
        }
        if (inside) {
            walkedInAt = clock.instant();
            return;
        }
        Instant walkedIn = walkedInAt;
        walkedInAt = null;
        if (walkedIn == null) return;
        Duration stay = Duration.between(walkedIn, clock.instant());
        if (stay.compareTo(MIN_STAY) < 0) {
            System.out.println("[*] Podcast: Out of the bathroom after " + stay.getSeconds() + "s; too short, waiting for the real trip.");
            return;
        }
        play(false);
    }

    /** Plays the latest episode. Returns false if there's no file, or (unless forced) it was already played. */
    public synchronized boolean play(boolean force) {
        long episode;
        try {
            episode = Files.getLastModifiedTime(podcast).toMillis();
        } catch (Exception e) {
            System.err.println("[-] Podcast: No episode at " + podcast + " (" + e.getMessage() + ")");
            return false;
        }
        if (!force && episode == playedEpisode) {
            System.out.println("[*] Podcast: Today's episode was already played; leaving the music alone.");
            return false;
        }
        playedEpisode = episode;
        musicManager.playPodcast(podcast.toAbsolutePath().toString(), lmsController.getAllRegisteredSpeakers());
        return true;
    }
}
