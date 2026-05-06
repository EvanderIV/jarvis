package com.jarvis;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

public class MusicManager {

    private final String MUSIC_DIR = "/home/evanm/Music/jarvis-music/"; 
    private final String INDEX_FILE = "music.json"; 
    
    // Snapcast default pipe
    private final String SNAPCAST_STREAM_ID = "House"; 
    private final String SNAPCAST_PIPE_PATH = "/tmp/snapfifo"; 

    private final SnapcastController snapcast;
    private final Gson gson;
    private List<Track> library = new ArrayList<>();
    
    // Theme translation dictionary
    private final Map<String, String[]> themeMap = new HashMap<>();
    
    private Process currentFfmpegProcess;
    private final Random random = new Random();

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

    public MusicManager(SnapcastController snapcast) {
        this.snapcast = snapcast;
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
        // OR + NOT Logic: Can be Jazz, Piano, or Relaxed, but MUST NOT be a Wakeup track
        themeMap.put("fancy_restaurant", new String[]{"Jazz", "Piano", "Relaxed", "-Wakeup"});
        
        // AND Logic: Track MUST be Happy AND MUST be Uplifting
        themeMap.put("happy_adventure", new String[]{"+Happy", "+Uplifting"});
        
        // Mixed Logic: MUST be Focused, MUST NOT be Epic, and can be either Ambient or Repetitive
        themeMap.put("study", new String[]{"+Focused", "-Epic", "Ambient", "Repetitive"});
        
        // Strict Exclusion: Workout tracks must be Upbeat, but exclude Somber/Relaxing ones
        themeMap.put("workout", new String[]{"Upbeat", "Driven", "Epic", "Dance", "-Somber", "-Relaxing"});
        
        // Sleep music: MUST NOT be Wakeup/Upbeat, can be Somber or Relaxing
        themeMap.put("sleep", new String[]{"Relaxing", "Somber", "-Wakeup", "-Upbeat", "-Tense"});
        
        // Generic Tavern: Mix of Folk and Celtic
        themeMap.put("tavern", new String[]{"Folk", "Celtic"});
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
     * Finds a matching track, routes the speakers, and starts playback.
     */
    public void playMusic(String parameter, List<String> targetMacs) {
        if (library.isEmpty()) {
            System.out.println("[-] Cannot play music, library is empty.");
            return;
        }

        // 1. Find matching tracks based on the Intent parameter
        List<Track> matches = findTracks(parameter);
        if (matches.isEmpty()) {
            System.out.println("[-] No tracks found matching: " + parameter + ". Defaulting to random.");
            matches = library; 
        }

        // 2. Pick a random track from the matches
        Track selectedTrack = matches.get(random.nextInt(matches.size()));
        String fullPath = Paths.get(MUSIC_DIR, selectedTrack.file).toString();
        
        System.out.println("[*] MusicManager: Selected '" + selectedTrack.title + "' for query: " + parameter);

        // 3. Route the Snapcast speakers to the correct stream
        snapcast.playStream(targetMacs, SNAPCAST_STREAM_ID);
        snapcast.unmute(targetMacs); 

        // 4. Start ffmpeg to pump the audio into the Snapcast FIFO pipe
        startFfmpegStream(fullPath, SNAPCAST_PIPE_PATH);
    }

    /**
     * Stops currently playing music.
     */
    public void stopMusic() {
        if (currentFfmpegProcess != null && currentFfmpegProcess.isAlive()) {
            currentFfmpegProcess.destroy();
            System.out.println("[*] MusicManager: Stopped playback.");
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
            String[] targetTags = themeMap.get(lowerQuery);
            return library.stream()
                .filter(track -> evaluateLogicTags(track, targetTags))
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

    /**
     * Handles the low-level OS process of piping ffmpeg data to Snapcast.
     */
    private void startFfmpegStream(String filePath, String pipePath) {
        stopMusic();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-re",
                "-i", filePath,
                "-f", "s16le",
                "-ar", "48000", 
                "-ac", "2",
                "-y", 
                pipePath
            );
            
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            
            currentFfmpegProcess = pb.start();
            System.out.println("[+] Playing via ffmpeg -> " + pipePath);
            
        } catch (Exception e) {
            System.err.println("[-] Failed to start ffmpeg: " + e.getMessage());
        }
    }
}