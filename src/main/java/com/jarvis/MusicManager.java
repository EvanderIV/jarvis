package com.jarvis;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    private final Set<String> playHistory = new LinkedHashSet<>();
    private List<String> targetMacs = new ArrayList<>();
    private String currentlyPlayingFile = null;
    private long lastTrackStartTime = 0;
    
    // Fadeout detection via rolling average audio level tracking
    private double volumeSum = 0;
    private int volumeSampleCount = 0;
    private double rollingAverageVolume = 0;
    private int lowVolumeCheckCount = 0;
    private static final int LOW_VOLUME_CHECK_THRESHOLD = 3; // Require consistent low volume over multiple checks
    private static final double LOW_VOLUME_RATIO = 0.5; // Detect as fadeout if level is below 50% of rolling average
    private static final double FADEOUT_REMAINING_SECONDS = 8.0; // Only check fadeout in final 8 seconds
    
    private Thread playbackMonitorThread = null;
    private volatile boolean isContinuousPlayEnabled = false;
    private volatile boolean shouldStopMonitoring = false;

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
     * "Tag"  : Track should have this tag (OR - must match at least one un-prefixed tag if provided)
     */
    private void initializeThemes() {
        // --- Exact Genre Translations ---
        themeMap.put("jazz",       new ThemeDefinition(new String[]{"Jazz"}, 0, 4));
        themeMap.put("classical",  new ThemeDefinition(new String[]{"Orchestral", "Piano"}, 0, 3));
        themeMap.put("electronic", new ThemeDefinition(new String[]{"Electronic"}, 1, 4));
        themeMap.put("rock",       new ThemeDefinition(new String[]{"Rock"}, 2, 4));
        themeMap.put("ambient",    new ThemeDefinition(new String[]{"Ambient"}, 0, 2));
        themeMap.put("country",    new ThemeDefinition(new String[]{"Country", "Folk"}, 1, 4)); 
        
        // Funk/Hip Hop
        themeMap.put("funk",       new ThemeDefinition(new String[]{"Funk", "Groove", "Jazz", "+Upbeat"}, 2, 4));
        themeMap.put("hip hop",    new ThemeDefinition(new String[]{"Hip Hop", "Rap", "Beat"}, 2, 4));

        // --- Vibe / Mood Translations (Using Logic & Speed) ---
        
        // "active": Energetic, workout, pump up (Must avoid chill vibes, High Speed)
        themeMap.put("active",     new ThemeDefinition(new String[]{"Upbeat", "Driven", "Epic", "-Somber", "-Relaxing"}, 3, 4));
        
        // "lofi": Chill, study, peaceful (Must be background friendly, Low Speed)
        themeMap.put("lofi",       new ThemeDefinition(new String[]{"Relaxing", "Focused", "Ambient", "-Tense", "-Epic", "-Driven"}, 0, 2));
        
        // "wakeup": Morning music (Use the explicit Wakeup tag or generally happy tracks, Mid-High Speed)
        themeMap.put("wakeup",     new ThemeDefinition(new String[]{"Wakeup", "Happy", "Upbeat", "Uplifting", "-Somber"}, 2, 3));
        
        // "fancy_restaurant": Dinner/elegant music (Smooth, unobtrusive, Low Speed)
        themeMap.put("fancy_restaurant", new ThemeDefinition(new String[]{"+Jazz", "Piano", "Relaxing", "Relaxed", "-Epic", "-Tense", "-Driven", "-Wakeup", "-Upbeat", "-Somber"}, 0, 2));
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
     * Finds a matching track, un-mutes the speakers, and instructs LMS to play it.
     * Also enables continuous playback within the same theme.
     */
    public void playMusic(String parameter, List<String> targetMacs) {
        if (library.isEmpty()) {
            System.out.println("[-] Cannot play music, library is empty.");
            return;
        }

        // Initialize continuous playback state
        this.currentTheme = parameter != null ? parameter : "default";
        this.targetMacs = new ArrayList<>(targetMacs);
        this.playHistory.clear();
        this.isContinuousPlayEnabled = true;
        this.shouldStopMonitoring = false;

        // Start or restart the playback monitor thread
        startPlaybackMonitor();

        // Play the first track
        playNextTrack();
    }

    /**
     * Plays the next track from the current theme, respecting play history to avoid repeats.
     * If all tracks have been played, resets history (keeping only the most recent track).
     */
    private void playNextTrack() {
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

        // 3. If all tracks have been played, reset history (keep only the most recent)
        if (availableTracks.isEmpty()) {
            if (!playHistory.isEmpty() && currentlyPlayingFile != null) {
                System.out.println("[*] MusicManager: All tracks exhausted, resetting history (keeping most recent).");
                playHistory.clear();
                playHistory.add(currentlyPlayingFile);
                
                // Re-filter for available tracks
                availableTracks = matches.stream()
                    .filter(track -> !playHistory.contains(track.file))
                    .collect(Collectors.toList());
            }
            
            // If still no available tracks, allow all
            if (availableTracks.isEmpty()) {
                availableTracks = matches;
            }
        }

        // 4. Pick a random track from available matches
        Track selectedTrack = availableTracks.get(random.nextInt(availableTracks.size()));
        String fullPath = Paths.get(MUSIC_DIR, selectedTrack.file).toString();
        
        System.out.println("[*] MusicManager: Selected '" + selectedTrack.title + "' for theme: " + currentTheme);

        // 5. Add to history
        addToHistory(selectedTrack.file);

        // 6. Command LMS to play the file
        currentlyPlayingFile = fullPath;
        lastTrackStartTime = System.currentTimeMillis();
        volumeSum = 0;
        volumeSampleCount = 0;
        rollingAverageVolume = 0;
        lowVolumeCheckCount = 0;
        
        lmsController.unmute(targetMacs);
        lmsController.playFile(targetMacs, fullPath);
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
     * Starts a background thread that monitors playback status and queues the next track
     * when the current one finishes.
     */
    private void startPlaybackMonitor() {
        // If a monitor thread is already running, don't start a new one
        if (playbackMonitorThread != null && playbackMonitorThread.isAlive()) {
            return;
        }

        playbackMonitorThread = new Thread(() -> {
            System.out.println("[*] MusicManager: Playback monitor started.");
            
            while (!shouldStopMonitoring && isContinuousPlayEnabled) {
                try {
                    // Check if current track has finished playing
                    if (hasTrackFinished()) {
                        System.out.println("[*] MusicManager: Current track finished, queuing next...");
                        playNextTrack();
                    }
                    
                    // Determine sleep duration based on time remaining in song
                    long sleepDuration = 2000; // Default 2 seconds
                    
                    // Get speaker list for status check
                    List<String> speakersToCheck = targetMacs;
                    if (speakersToCheck.isEmpty()) {
                        speakersToCheck = lmsController.getAllRegisteredSpeakers();
                    }
                    
                    // If we have speakers, check remaining time to adjust sleep duration
                    if (!speakersToCheck.isEmpty() && currentlyPlayingFile != null) {
                        String firstSpeaker = speakersToCheck.get(0);
                        Map<String, Object> status = lmsController.getPlaybackStatus(firstSpeaker);
                        
                        if (!status.isEmpty()) {
                            Double duration = (Double) status.get("duration");
                            Double currentTime = (Double) status.get("time");
                            
                            if (duration != null && duration > 0 && currentTime != null) {
                                double remainingTime = duration - currentTime;
                                
                                // Use 0.5 second checks in the fadeout detection window
                                if (remainingTime <= FADEOUT_REMAINING_SECONDS && remainingTime > 0) {
                                    sleepDuration = 500;
                                }
                            }
                        }
                    }
                    
                    Thread.sleep(sleepDuration);

                    if (shouldStopMonitoring || !isContinuousPlayEnabled) {
                        break;
                    }
                } catch (InterruptedException e) {
                    System.out.println("[*] MusicManager: Playback monitor interrupted.");
                    break;
                }
            }
            
            System.out.println("[*] MusicManager: Playback monitor stopped.");
        }, "MusicPlaybackMonitor");
        
        playbackMonitorThread.setDaemon(true);
        playbackMonitorThread.start();
    }

    /**
     * Detects if the currently playing track has finished or should be skipped due to fadeout.
     * Queries LMS status to check if playback has stopped or moved to a new file.
     * Detects fadeout by tracking audio level against a rolling average of the song's volume.
     */
    private boolean hasTrackFinished() {
        if (currentlyPlayingFile == null) {
            return false;
        }

        // Give at least 2 seconds before considering a track finished
        // (to avoid rapid re-queueing at start of playback)
        long elapsedTime = System.currentTimeMillis() - lastTrackStartTime;
        if (elapsedTime < 2000) {
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

        // If the player has switched to a different file, the track has finished
        String currentFile = (String) status.get("currentFile");
        if (currentFile != null && !currentlyPlayingFile.contains(currentFile)) {
            return true;
        }

        // Track rolling average volume throughout the song and check for fadeout
        Double duration = (Double) status.get("duration");
        Double currentTime = (Double) status.get("time");
        Double audioLevel = (Double) status.get("level");
        
        if (duration != null && duration > 0 && currentTime != null && audioLevel != null) {
            double remainingTime = duration - currentTime;
            
            // Update rolling average with each sample
            volumeSum += audioLevel;
            volumeSampleCount++;
            rollingAverageVolume = volumeSum / volumeSampleCount;
            
            // Only check for fadeout in the final 8 seconds
            if (remainingTime <= FADEOUT_REMAINING_SECONDS && remainingTime > 0) {
                // Check if audio level is consistently below 50% of the rolling average
                double volumeThreshold = rollingAverageVolume * LOW_VOLUME_RATIO;
                
                if (App.DEBUG_MODE) {
                    System.out.println("[DEBUG] Fadeout check: level=" + String.format("%.1f", audioLevel) +
                                     ", avg=" + String.format("%.1f", rollingAverageVolume) +
                                     ", threshold=" + String.format("%.1f", volumeThreshold) +
                                     ", belowThreshold=" + (audioLevel < volumeThreshold) +
                                     ", lowVolumeCount=" + lowVolumeCheckCount +
                                     ", remaining=" + String.format("%.1f", remainingTime) + "s");
                }
                
                if (audioLevel < volumeThreshold) {
                    lowVolumeCheckCount++;
                    
                    if (lowVolumeCheckCount >= LOW_VOLUME_CHECK_THRESHOLD) {
                        System.out.println("[*] MusicManager: Detected fadeout (" + 
                                         String.format("%.1f", remainingTime) + 
                                         "s remaining, level: " + String.format("%.1f", audioLevel) +
                                         ", avg: " + String.format("%.1f", rollingAverageVolume) +
                                         "). Skipping to next song.");
                        lowVolumeCheckCount = 0;
                        return true;
                    }
                } else {
                    lowVolumeCheckCount = 0; // Reset if level is above threshold
                }
            } else {
                lowVolumeCheckCount = 0; // Reset if not in final window
            }
        }

        return false;
    }

    /**
     * Stops currently playing music and disables continuous playback.
     */
    public void stopMusic() {
        System.out.println("[*] MusicManager: Stopping playback and disabling continuous mode.");
        
        // Disable continuous playback
        isContinuousPlayEnabled = false;
        shouldStopMonitoring = true;
        
        // Wait for the monitor thread to finish (with timeout)
        if (playbackMonitorThread != null && playbackMonitorThread.isAlive()) {
            try {
                playbackMonitorThread.join(3000); // Wait up to 3 seconds
            } catch (InterruptedException e) {
                System.err.println("[-] Interrupted waiting for playback monitor to stop.");
            }
        }
        
        // Clear state
        currentTheme = null;
        currentlyPlayingFile = null;
        volumeSum = 0;
        volumeSampleCount = 0;
        rollingAverageVolume = 0;
        lowVolumeCheckCount = 0;
        playHistory.clear();
        
        // An empty list tells the LmsController to stop playback on ALL registered speakers
        lmsController.stop(new ArrayList<>()); 
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

        // 2. Otherwise, do a direct search (e.g. if the user explicitly asked for "Jazz" or a specific title)
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

        // If there were any standard OR conditions provided, the track MUST match at least one.
        // If there were ONLY AND/NOT conditions, we bypass this check (hasOrConditions will be false).
        if (hasOrConditions && !matchedOrCondition) {
            return false;
        }

        return true;
    }

    /**
     * Core matching logic: checks genres, moods, and title against a single raw keyword.
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
}