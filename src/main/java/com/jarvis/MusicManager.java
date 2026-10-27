package com.jarvis;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class MusicManager {

    private final String MUSIC_DIR = "/home/evanm/Music/jarvis-music/";
    private final String INDEX_FILE = "music.json";
    private static final int MAX_HISTORY_SIZE = 50;

    private final LmsController lmsController;
    private final Gson gson;
    private List<Track> library = new ArrayList<>();

    // Theme translation dictionary
    private final Map<String, ThemeDefinition> themeMap = new HashMap<>();

    private final Random random = new Random();

    // Continuous playback state
    private String currentTheme = null;
    private int currentMinSpeed = 0;
    private int currentMaxSpeed = 4;
    private final Set<String> playHistory = new LinkedHashSet<>();
    private volatile List<String> targetMacs = new ArrayList<>();
    private volatile String currentlyPlayingFile = null;
    private volatile long lastTrackStartTime = 0;
    private volatile float currentTrackFadeoutTimestamp = -1; // Fadeout point for current track, or -1 if none detected

    // Volume tracking for mute/unmute restore
    private final Map<String, Integer> savedVolumes = new HashMap<>();

    // Tags (lowercase, no +/- prefix) that restrict playback to speakers whose alias contains the mapped substring
    private static final Map<String, String> TAG_SPEAKER_RESTRICTIONS = new HashMap<>();
    static {
        TAG_SPEAKER_RESTRICTIONS.put("asmr", "bedroom");
    }
    private boolean speakerRestrictionActive = false;

    // Playback state is touched by the command worker, the playback monitor, routine
    // and timer threads; public entry points synchronize on this MusicManager.
    // The monitor thread exits as soon as it is no longer the one referenced here.
    private volatile Thread playbackMonitorThread = null;
    private volatile boolean isContinuousPlayEnabled = false;

    // A podcast plays as if it were one long track in the continuous-play loop, so the
    // monitor notices when it ends and routine theme switches queue up behind it.
    private static final double PODCAST_LET_FINISH_SECONDS = 15;
    private boolean playingPodcast = false;
    private String pendingPodcast = null; // starts when the current track ends

    // --- Data Model for JSON Parsing ---

    public static class LibraryRoot {
        public List<Track> music;
    }

    public static class Track {
        public String file;
        public String title;
        public List<String> genres;
        public List<String> moods;
        public int speed;
        // Pre-computed offline by FadeoutIndexer and stored in music.json.
        // null = never analyzed, -1 = analyzed with no fadeout, > 0 = skip point (seconds).
        public Float fadeoutTimestamp;
    }

    // Inner class to bind logic tags with allowed speed ranges
    public static class ThemeDefinition {
        public final String[] tags;
        public final int minSpeed;
        public final int maxSpeed;

        public ThemeDefinition(String[] tags, int minSpeed, int maxSpeed) {
            this.tags = tags;
            this.minSpeed = minSpeed;
            this.maxSpeed = maxSpeed;
        }
    }

    public MusicManager(LmsController lmsController) {
        this.lmsController = lmsController;
        this.gson = new Gson();
        loadLibrary();
        initializeThemes();
    }

    /**
     * Translates conversational phrases into metadata tags found in music.json.
     * Logic Prefixes:
     * "+Tag" : Track MUST have this tag (AND)
     * "-Tag" : Track MUST NOT have this tag (NOT)
     * "Tag" : Track should have this tag (OR - must match at least one un-prefixed
     * tag if provided)
     */
    private void initializeThemes() {
        // --- Exact Genre Translations ---
        themeMap.put("jazz", new ThemeDefinition(new String[] { "Jazz", "-Meme" }, 0, 4));
        themeMap.put("classical", new ThemeDefinition(new String[] { "Orchestral", "Piano", "-Meme" }, 0, 3));
        themeMap.put("electronic", new ThemeDefinition(new String[] { "Electronic", "-Meme" }, 1, 4));
        themeMap.put("rock", new ThemeDefinition(new String[] { "Rock", "-Meme" }, 2, 4));
        themeMap.put("ambient", new ThemeDefinition(new String[] { "Ambient", "-Meme" }, 0, 2));
        themeMap.put("country", new ThemeDefinition(new String[] { "Folk", "Country", "-Meme" }, 1, 4));

        // Funk/Hip Hop
        themeMap.put("funk", new ThemeDefinition(new String[] { "+Upbeat", "Funk", "Groove", "Jazz", "-Meme" }, 2, 4));
        themeMap.put("hip hop", new ThemeDefinition(new String[] { "Hip Hop", "Rap", "Beat", "-Meme" }, 2, 4));

        // --- Vibe / Mood Translations (Using Logic & Speed) ---

        // "active": Energetic, workout, pump up (Must avoid chill vibes, High Speed)
        themeMap.put("active",
                new ThemeDefinition(
                        new String[] { "Upbeat", "Driven", "-Epic", "-Somber", "-Relaxing", "-Meme", "-Profanity" },
                        3, 4));

        themeMap.put("epic",
                new ThemeDefinition(new String[] { "+Epic", "Upbeat", "Driven", "-Meme", "-Relaxing" }, 2, 4));

        // "lofi": Chill, study, peaceful (Must be background friendly, Low Speed)
        themeMap.put("lofi", new ThemeDefinition(
                new String[] { "+Focused", "Relaxing", "Ambient", "-Tense", "-Epic", "-Driven", "-Meme", "-Lyrics" }, 1,
                2));

        themeMap.put("relaxing", new ThemeDefinition(
                new String[] { "+Relaxing", "Ambient", "Focused", "-Tense", "-Epic", "-Driven", "-Repetitive",
                        "-Mystical", "-Meme", "-Lyrics" },
                0, 1));

        // "wakeup": Morning music (Use the explicit Wakeup tag or generally happy
        // tracks, Mid-High Speed)
        themeMap.put("wakeup",
                new ThemeDefinition(new String[] { "+Wakeup", "Happy", "Upbeat", "Uplifting", "-Somber", "-Lyrics" }, 2,
                        3));

        // "fancy_restaurant": Dinner/elegant music (Smooth, unobtrusive, Low Speed)
        themeMap.put("fancy_restaurant", new ThemeDefinition(new String[] { "+Jazz", "Piano", "Relaxing",
                "-Epic", "-Tense", "-Driven", "-Wakeup", "-Somber", "-Mystical", "-Meme", "-Lyrics", "-Repetitive" }, 0,
                2));

        themeMap.put("meme",
                new ThemeDefinition(new String[] { "+Meme" }, 0, 4));

        themeMap.put("cooking curry",
                new ThemeDefinition(new String[] { "+Cooking Curry" }, 1, 4));
        themeMap.put("jorkening",
                new ThemeDefinition(new String[] { "+Jorkening" }, 0, 4));
        themeMap.put("microwave popcorn",
                new ThemeDefinition(new String[] { "+Microwave Popcorn" }, 0, 4));
    }

    /**
     * Reads the music.json file and populates the library memory.
     */
    private void loadLibrary() {
        Path indexPath = Paths.get(MUSIC_DIR, INDEX_FILE);

        if (!Files.exists(indexPath)) {
            indexPath = Paths.get("/home/evanm/Music/", INDEX_FILE);
        }

        if (!Files.exists(indexPath)) {
            System.err.println("[-] Music Index not found at: " + indexPath.toString());
            return;
        }

        try (Reader reader = Files.newBufferedReader(indexPath)) {
            LibraryRoot root = gson.fromJson(reader, LibraryRoot.class);

            if (root != null && root.music != null) {
                library = root.music;
                System.out.println("[+] MusicManager: Loaded " + library.size() + " tracks from index.");
            } else {
                System.err.println("[-] Failed to load music: The 'music' array is missing or empty in music.json");
            }
        } catch (Exception e) {
            System.err.println("[-] Failed to load music index: " + e.getMessage());
        }
    }

    /**
     * Finds a matching track and instructs LMS to play it.
     * Also enables continuous playback within the same theme.
     */
    public void playMusic(String parameter, List<String> targetMacs) {
        playMusic(parameter, targetMacs, 0, 4);
    }

    public synchronized void playMusic(String parameter, List<String> targetMacs, int minSpeed, int maxSpeed) {
        if (library.isEmpty()) {
            System.out.println("[-] Cannot play music, library is empty.");
            return;
        }

        // Initialize continuous playback state
        this.currentTheme = parameter != null ? parameter : "default";
        this.currentMinSpeed = minSpeed;
        this.currentMaxSpeed = maxSpeed;
        this.targetMacs = new ArrayList<>(targetMacs);
        this.isContinuousPlayEnabled = true;
        // An explicit play request replaces a podcast (playing or waiting to start)
        this.playingPodcast = false;
        this.pendingPodcast = null;

        applySpeakerRestrictions(this.currentTheme);

        // Start or restart the playback monitor thread
        startPlaybackMonitor();

        // Play the first track
        playNextTrack();
    }

    /**
     * Plays the next track from the current theme, respecting play history to avoid
     * repeats.
     * If all tracks have been played, resets history (keeping only the most recent
     * track).
     */
    private synchronized void playNextTrack() {
        if (pendingPodcast != null) {
            startPodcast(pendingPodcast, targetMacs);
            return;
        }
        if (playingPodcast) {
            playingPodcast = false;
            if (currentTheme == null) {
                // Nothing was playing before the podcast, so there's nothing to go back to
                System.out.println("[*] MusicManager: Podcast finished.");
                isContinuousPlayEnabled = false;
                playbackMonitorThread = null;
                currentlyPlayingFile = null;
                return;
            }
            System.out.println("[*] MusicManager: Podcast finished, resuming theme '" + currentTheme + "'.");
        }

        if (!isContinuousPlayEnabled || currentTheme == null) {
            return;
        }

        if (library.isEmpty()) {
            System.out.println("[-] Cannot play music, library is empty.");
            return;
        }

        // 1. Find matching tracks based on the current theme
        List<Track> matches = findTracks(currentTheme);
        if (matches.isEmpty()) {
            System.out.println("[-] No tracks found matching theme: " + currentTheme + ". Defaulting to random.");
            matches = library;
        }

        // 2. Filter out tracks that are in the history
        List<Track> availableTracks = matches.stream()
                .filter(track -> !playHistory.contains(track.file))
                .collect(Collectors.toList());

        // 3. If all theme-matching tracks are in history, evict oldest entries one at a time
        //    until a track opens up — preserving as much cross-theme de-dupe coverage as possible.
        if (availableTracks.isEmpty()) {
            System.out.println("[*] MusicManager: All tracks exhausted for theme '" + currentTheme + "', evicting oldest history entries.");
            java.util.Iterator<String> oldest = playHistory.iterator();
            while (oldest.hasNext() && availableTracks.isEmpty()) {
                oldest.next();
                oldest.remove();
                availableTracks = matches.stream()
                        .filter(track -> !playHistory.contains(track.file))
                        .collect(Collectors.toList());
            }
            if (availableTracks.isEmpty()) {
                availableTracks = matches;
            }
        }

        // 4. Pick a random track from available matches
        Track selectedTrack = availableTracks.get(random.nextInt(availableTracks.size()));
        String fullPath = resolvePlayablePath(MUSIC_DIR, selectedTrack.file);

        System.out.println("[*] MusicManager: Selected '" + selectedTrack.title + "' for theme: " + currentTheme);

        // 5. Add to history
        addToHistory(selectedTrack.file);

        // 6. Play the file IMMEDIATELY
        currentlyPlayingFile = fullPath;
        lastTrackStartTime = System.currentTimeMillis();
        // Fadeout timestamp is pre-computed offline by FadeoutIndexer and stored in music.json.
        // null = never analyzed, -1 = analyzed with no fadeout; both disable early-skip
        // (only a value > 0 triggers the fadeout skip in hasTrackFinished()).
        currentTrackFadeoutTimestamp = (selectedTrack.fadeoutTimestamp != null)
                ? selectedTrack.fadeoutTimestamp
                : -1f;

        // lmsController.unmute(targetMacs); // Pause functionality already prevents
        // sound output

        // Restore saved volume for each speaker
        for (String mac : targetMacs) {
            if (savedVolumes.containsKey(mac)) {
                int savedVolume = savedVolumes.get(mac);
                lmsController.setVolume(Arrays.asList(mac), savedVolume);
                System.out.println("[*] MusicManager: Restored volume " + savedVolume + "% for " + mac);
            }
        }

        lmsController.playFile(targetMacs, fullPath);
    }

    /**
     * Returns the path to play for a music.json entry. compress-music.sh re-encodes
     * FLAC/WebM/etc. on the server to "<same name>.mp3" without touching music.json,
     * so when the listed file is gone but its .mp3 twin exists, play that instead.
     */
    static String resolvePlayablePath(String musicDir, String file) {
        Path listed = Paths.get(musicDir, file);
        if (!Files.exists(listed)) {
            int dot = file.lastIndexOf('.');
            if (dot > 0) {
                Path mp3 = Paths.get(musicDir, file.substring(0, dot) + ".mp3");
                if (Files.exists(mp3)) {
                    return mp3.toString();
                }
            }
        }
        return listed.toString();
    }

    /**
     * Adds a track to the play history, enforcing the max size limit.
     */
    private void addToHistory(String trackFile) {
        playHistory.add(trackFile);

        // Enforce max history size by removing the oldest entry
        if (playHistory.size() > MAX_HISTORY_SIZE) {
            // LinkedHashSet maintains insertion order, so remove the first (oldest) element
            playHistory.remove(playHistory.iterator().next());
        }
    }

    /**
     * Starts a background thread that monitors playback status and queues the next
     * track
     * when the current one finishes.
     */
    private synchronized void startPlaybackMonitor() {
        // If a monitor thread is already running, don't start a new one
        if (playbackMonitorThread != null && playbackMonitorThread.isAlive()) {
            return;
        }

        Thread monitor = new Thread(() -> {
            System.out.println("[*] MusicManager: Playback monitor started.");

            while (playbackMonitorThread == Thread.currentThread() && isContinuousPlayEnabled) {
                try {
                    // Check if current track has finished playing
                    String fileAtCheck = currentlyPlayingFile;
                    if (hasTrackFinished()) {
                        advanceIfStillCurrent(fileAtCheck);
                    }

                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    System.out.println("[*] MusicManager: Playback monitor interrupted.");
                    break;
                }
            }

            System.out.println("[*] MusicManager: Playback monitor stopped.");
        }, "MusicPlaybackMonitor");

        monitor.setDaemon(true);
        playbackMonitorThread = monitor;
        monitor.start();
    }

    /**
     * Queues the next track only if nothing changed while we were polling LMS —
     * otherwise a stop or a new play request that landed mid-poll would be undone.
     */
    private synchronized void advanceIfStillCurrent(String fileAtCheck) {
        if (playbackMonitorThread != Thread.currentThread() || !isContinuousPlayEnabled
                || fileAtCheck == null || !fileAtCheck.equals(currentlyPlayingFile)) {
            return;
        }
        System.out.println("[*] MusicManager: Current track finished, queuing next...");
        playNextTrack();
    }

    /**
     * Detects if the currently playing track has finished.
     * Queries LMS status to check if playback has stopped or moved to a new file.
     */
    private boolean hasTrackFinished() {
        if (currentlyPlayingFile == null) {
            return false;
        }

        // Wait at least 10 seconds before checking for track completion.
        // LMS can take several seconds to transition from "stop" to "play" after
        // receiving a playlist play command, so a shorter guard causes the monitor
        // to see the loading gap as a finished track and immediately queue another.
        long elapsedTime = System.currentTimeMillis() - lastTrackStartTime;
        if (elapsedTime < 10000) {
            return false;
        }

        // If targetMacs is empty, resolve to all registered speakers
        List<String> speakersToCheck = targetMacs;
        if (speakersToCheck.isEmpty()) {
            speakersToCheck = lmsController.getAllRegisteredSpeakers();
        }

        // If we still have no speakers to check, can't determine status
        if (speakersToCheck.isEmpty()) {
            return false;
        }

        // Query the first available speaker for playback status
        String firstSpeaker = speakersToCheck.get(0);
        Map<String, Object> status = lmsController.getPlaybackStatus(firstSpeaker);

        if (status.isEmpty()) {
            // If we can't get status, assume playback hasn't finished
            return false;
        }

        String mode = (String) status.get("mode");

        // If the player is stopped, the track has finished
        if ("stop".equals(mode)) {
            return true;
        }

        // Check if we've reached the fadeout point (pre-analyzed silent section)
        if (currentTrackFadeoutTimestamp > 0) {
            Double currentTime = (Double) status.get("time");

            if (currentTime != null && currentTime >= currentTrackFadeoutTimestamp) {
                if (App.DEBUG_MODE) {
                    System.out.println("[DEBUG] MusicManager: Reached fadeout point at " +
                            String.format("%.1f", currentTime) + "s (threshold: " +
                            String.format("%.1f", currentTrackFadeoutTimestamp) + "s)");
                }
                System.out.println("[*] MusicManager: Fadeout detected, skipping to next song.");
                return true;
            }
        }

        return false;
    }

    public boolean isPlaying() {
        return isContinuousPlayEnabled;
    }

    /**
     * Plays a podcast in place of the music. A track with under PODCAST_LET_FINISH_SECONDS
     * left is allowed to finish first; otherwise it's cut off. When the podcast ends, the
     * music theme that was playing resumes with a new track — including any theme change a
     * routine queued in the meantime. If nothing was playing, playback just stops.
     *
     * @param idleSpeakers where to play when no music is playing (otherwise the music's speakers are used)
     */
    public synchronized void playPodcast(String path, List<String> idleSpeakers) {
        if (playingPodcast || pendingPodcast != null) {
            System.out.println("[*] MusicManager: A podcast is already playing or queued.");
            return;
        }
        if (isContinuousPlayEnabled && currentlyPlayingFile != null) {
            double left = secondsLeftInCurrentTrack();
            if (left >= 0 && left < PODCAST_LET_FINISH_SECONDS) {
                System.out.println("[*] MusicManager: Podcast queued; letting the current track finish ("
                        + Math.round(left) + "s left).");
                pendingPodcast = path;
                return;
            }
        }
        startPodcast(path, idleSpeakers);
    }

    private synchronized void startPodcast(String path, List<String> idleSpeakers) {
        pendingPodcast = null;
        if (!isContinuousPlayEnabled || currentTheme == null) {
            // Speakers may be muted or at a routine's low volume from earlier; bring them to their defaults
            currentTheme = null;
            targetMacs = new ArrayList<>(idleSpeakers);
            lmsController.unmute(targetMacs);
            for (String mac : targetMacs) {
                lmsController.setVolume(List.of(mac), lmsController.getDefaultVolume(mac, 60));
            }
        }
        System.out.println("[*] MusicManager: Playing podcast " + path
                + (currentTheme != null ? " (theme '" + currentTheme + "' resumes after)" : ""));
        playingPodcast = true;
        isContinuousPlayEnabled = true;
        currentlyPlayingFile = path;
        lastTrackStartTime = System.currentTimeMillis();
        currentTrackFadeoutTimestamp = -1;
        startPlaybackMonitor();
        lmsController.playFile(targetMacs, path);
    }

    /** Seconds until the monitor moves on from the current track (fadeout point or end), or -1 if unknown. */
    private double secondsLeftInCurrentTrack() {
        List<String> speakers = targetMacs.isEmpty() ? lmsController.getAllRegisteredSpeakers() : targetMacs;
        if (speakers.isEmpty()) return -1;
        Map<String, Object> status = lmsController.getPlaybackStatus(speakers.get(0));
        Double time = (Double) status.get("time");
        Double duration = (Double) status.get("duration");
        if (time == null || duration == null || duration <= 0) return -1;
        double end = currentTrackFadeoutTimestamp > 0 ? Math.min(duration, currentTrackFadeoutTimestamp) : duration;
        return Math.max(0, end - time);
    }

    /**
     * Switches to a new theme without interrupting the currently playing track.
     * If music is already running, the new theme takes effect on the next track.
     * If nothing is playing, starts playback immediately.
     */
    public void switchTheme(String newTheme, List<String> targetMacs) {
        switchTheme(newTheme, targetMacs, 0, 4);
    }

    /**
     * switchTheme, but only if stillWanted is true once the lock is held. Routines use
     * this so a step that was already running when the user said "kill the music"
     * can't restart playback after the stop goes through.
     */
    public synchronized void switchThemeIf(java.util.function.BooleanSupplier stillWanted, String newTheme,
            List<String> targetMacs, int minSpeed, int maxSpeed) {
        if (!stillWanted.getAsBoolean()) {
            return;
        }
        switchTheme(newTheme, targetMacs, minSpeed, maxSpeed);
    }

    public synchronized void switchTheme(String newTheme, List<String> targetMacs, int minSpeed, int maxSpeed) {
        if (isContinuousPlayEnabled && currentlyPlayingFile != null) {
            System.out.println("[*] MusicManager: Queuing theme switch to '" + newTheme + "' after current track.");
            this.currentTheme = newTheme;
            this.currentMinSpeed = minSpeed;
            this.currentMaxSpeed = maxSpeed;
            applySpeakerRestrictions(newTheme);
        } else {
            playMusic(newTheme, targetMacs, minSpeed, maxSpeed);
        }
    }

    /**
     * Stops currently playing music and disables continuous playback.
     */
    public synchronized void stopMusic() {
        System.out.println("[*] MusicManager: Stopping playback and disabling continuous mode.");

        // Save the current volume from all target speakers before stopping
        // List<String> speakersToCheck = targetMacs;
        // if (speakersToCheck.isEmpty()) {
        // speakersToCheck = lmsController.getAllRegisteredSpeakers();
        // }
        // for (String mac : speakersToCheck) {
        // int volume = lmsController.getVolume(mac);
        // if (volume >= 0) {
        // savedVolumes.put(mac, volume);
        // System.out.println("[*] MusicManager: Saved volume " + volume + "% for " +
        // mac);
        // }
        // }

        // Disable continuous playback. Detaching the monitor (rather than joining it)
        // makes it exit on its next wake-up without delaying the stop command; the
        // lock guarantees it can't queue another track after this point.
        isContinuousPlayEnabled = false;
        playbackMonitorThread = null;

        // Clear state
        playingPodcast = false;
        pendingPodcast = null;
        currentTheme = null;
        currentlyPlayingFile = null;
        currentTrackFadeoutTimestamp = -1;

        // An empty list tells the LmsController to stop playback on ALL registered
        // speakers
        lmsController.stop(new ArrayList<>());

        // Lift a speaker restriction (e.g. ASMR -> bedroom only) now, while nothing is
        // playing. Otherwise it would be lifted by the next play request, which resets
        // every speaker to its default volume right as that music starts — blasting a
        // routine that wanted to fade in from silence.
        if (speakerRestrictionActive) {
            restoreAllSpeakers();
        }
    }

    /**
     * Filters the library based on mapped themes, genres, moods, or title.
     */
    private List<Track> findTracks(String query) {
        if (query == null || query.equalsIgnoreCase("default")) {
            return new ArrayList<>(library);
        }

        String lowerQuery = query.toLowerCase();

        // 1. Check if the requested query is a known mapped theme
        if (themeMap.containsKey(lowerQuery)) {
            ThemeDefinition theme = themeMap.get(lowerQuery);
            return library.stream()
                    .filter(track -> track.speed >= theme.minSpeed && track.speed <= theme.maxSpeed)
                    .filter(track -> evaluateLogicTags(track, theme.tags))
                    .collect(Collectors.toList());
        }

        // 2. Inline tag expression (e.g. "+Relaxing active -Somber") — split and evaluate directly
        if (lowerQuery.contains("+") || lowerQuery.contains("-") || lowerQuery.contains(" ")) {
            String[] tags = lowerQuery.trim().split("\\s+");
            final int minSpd = currentMinSpeed;
            final int maxSpd = currentMaxSpeed;
            return library.stream()
                    .filter(track -> track.speed >= minSpd && track.speed <= maxSpd)
                    .filter(track -> evaluateLogicTags(track, tags))
                    .collect(Collectors.toList());
        }

        // 3. Otherwise, do a direct search (e.g. if the user explicitly asked for
        // "Jazz" or a specific title)
        return library.stream()
                .filter(track -> matchesSingleKeyword(track, lowerQuery))
                .collect(Collectors.toList());
    }

    /**
     * Evaluates a track against a set of logic-prefixed tags (+ AND, - NOT, OR)
     */
    private boolean evaluateLogicTags(Track track, String[] targetTags) {
        boolean hasOrConditions = false;
        boolean matchedOrCondition = false;

        for (String tag : targetTags) {
            if (tag.startsWith("-")) {
                // NOT condition (Must NOT have this tag)
                String actualTag = tag.substring(1).toLowerCase();
                if (matchesSingleKeyword(track, actualTag)) {
                    return false; // Instantly reject track
                }
            } else if (tag.startsWith("+")) {
                // AND condition (Must HAVE this tag)
                String actualTag = tag.substring(1).toLowerCase();
                if (!matchesSingleKeyword(track, actualTag)) {
                    return false; // Instantly reject if missing
                }
            } else {
                // OR condition (Should have this tag)
                hasOrConditions = true;
                if (matchesSingleKeyword(track, tag.toLowerCase())) {
                    matchedOrCondition = true;
                }
            }
        }

        // If there were any standard OR conditions provided, the track MUST match at
        // least one.
        // If there were ONLY AND/NOT conditions, we bypass this check (hasOrConditions
        // will be false).
        if (hasOrConditions && !matchedOrCondition) {
            return false;
        }

        return true;
    }

    /**
     * Core matching logic: checks genres, moods, and title against a single raw
     * keyword.
     */
    private boolean matchesSingleKeyword(Track track, String keyword) {
        if (track.genres != null && track.genres.stream().anyMatch(g -> g.toLowerCase().contains(keyword))) {
            return true;
        }
        if (track.moods != null && track.moods.stream().anyMatch(m -> m.toLowerCase().contains(keyword))) {
            return true;
        }
        if (track.title != null && track.title.toLowerCase().contains(keyword)) {
            return true;
        }
        return false;
    }

    private void applySpeakerRestrictions(String theme) {
        if (theme == null || theme.equalsIgnoreCase("default")) {
            if (speakerRestrictionActive) restoreAllSpeakers();
            return;
        }

        String restrictedAlias = null;
        for (String tag : theme.toLowerCase().trim().split("\\s+")) {
            String clean = tag.replaceAll("^[+-]", "");
            if (TAG_SPEAKER_RESTRICTIONS.containsKey(clean)) {
                restrictedAlias = TAG_SPEAKER_RESTRICTIONS.get(clean);
                break;
            }
        }

        List<String> allSpeakers = lmsController.getAllRegisteredSpeakers();

        if (restrictedAlias != null) {
            final String alias = restrictedAlias;
            List<String> allowed = allSpeakers.stream()
                    .filter(mac -> { String a = lmsController.getAlias(mac); return a != null && a.toLowerCase().contains(alias); })
                    .collect(Collectors.toList());
            List<String> toMute = allSpeakers.stream()
                    .filter(mac -> !allowed.contains(mac))
                    .collect(Collectors.toList());
            if (!toMute.isEmpty()) {
                lmsController.setVolume(toMute, 0);
                System.out.println("[*] MusicManager: Muted " + toMute.size() + " speaker(s) for restricted theme (allowed: " + alias + ").");
            }
            this.targetMacs = allowed;
            speakerRestrictionActive = true;
        } else if (speakerRestrictionActive) {
            restoreAllSpeakers();
        }
    }

    private void restoreAllSpeakers() {
        List<String> allSpeakers = lmsController.getAllRegisteredSpeakers();
        for (String mac : allSpeakers) {
            lmsController.setVolume(List.of(mac), lmsController.getDefaultVolume(mac, 60));
        }
        this.targetMacs = allSpeakers;
        speakerRestrictionActive = false;
        System.out.println("[*] MusicManager: Restored all speaker volumes after restricted theme ended.");
    }
}