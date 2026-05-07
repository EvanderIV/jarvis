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

    private final LmsController lmsController;
    private final Gson gson;
    private List<Track> library = new ArrayList<>();
    
    // Theme translation dictionary
    private final Map<String, ThemeDefinition> themeMap = new HashMap<>();
    
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
        themeMap.put("fancy_restaurant", new ThemeDefinition(new String[]{"Jazz", "Piano", "Relaxing", "Relaxed", "-Epic", "-Tense", "-Driven", "-Wakeup", "-Upbeat", "-Somber"}, 0, 2));
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

        // 3. Command LMS to play the file natively
        lmsController.unmute(targetMacs); 
        lmsController.playFile(targetMacs, fullPath); 
    }

    /**
     * Stops currently playing music.
     */
    public void stopMusic() {
        System.out.println("[*] MusicManager: Stopping playback.");
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