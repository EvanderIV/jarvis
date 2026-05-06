package com.jarvis;

import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class MusicManager {

    private final String MUSIC_DIR = "/home/evanm/Music/jarvis-music/"; 
    private final String INDEX_FILE = "music.json"; 
    
    // Snapcast default pipe. If you set up multiple streams later, you can parameterize this!
    private final String SNAPCAST_STREAM_ID = "House"; 
    private final String SNAPCAST_PIPE_PATH = "/tmp/snapfifo"; 

    private final SnapcastController snapcast;
    private final Gson gson;
    private List<Track> library = new ArrayList<>();
    
    private Process currentFfmpegProcess;
    private final Random random = new Random();

    // --- Data Model for JSON Parsing ---
    
    // Wrapper class to match the {"music": [...]} root object structure
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
    }

    /**
     * Reads the music.json file and populates the library memory.
     */
    private void loadLibrary() {
        // Look for the JSON file
        Path indexPath = Paths.get(MUSIC_DIR, INDEX_FILE);
        
        // Fallback to the parent Music folder if it isn't in jarvis-music
        if (!Files.exists(indexPath)) {
            indexPath = Paths.get("/home/evanm/Music/", INDEX_FILE);
        }

        if (!Files.exists(indexPath)) {
            System.err.println("[-] Music Index not found at: " + indexPath.toString());
            return;
        }

        try (Reader reader = Files.newBufferedReader(indexPath)) {
            // Parse into the wrapper object instead of a direct List
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
            matches = library; // Fallback to entire library if the genre/mood isn't found
        }

        // 2. Pick a random track from the matches
        Track selectedTrack = matches.get(random.nextInt(matches.size()));
        String fullPath = Paths.get(MUSIC_DIR, selectedTrack.file).toString();
        
        System.out.println("[*] MusicManager: Selected '" + selectedTrack.title + "' for query: " + parameter);

        // 3. Route the Snapcast speakers to the correct stream
        snapcast.playStream(targetMacs, SNAPCAST_STREAM_ID);
        snapcast.unmute(targetMacs); // Make sure they aren't muted!

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
     * Filters the library based on genres, moods, or title.
     */
    private List<Track> findTracks(String query) {
        if (query == null || query.equalsIgnoreCase("default")) {
            return new ArrayList<>(library);
        }

        String q = query.toLowerCase();
        return library.stream().filter(t -> 
            (t.genres != null && t.genres.stream().anyMatch(g -> g.toLowerCase().contains(q))) ||
            (t.moods != null && t.moods.stream().anyMatch(m -> m.toLowerCase().contains(q))) ||
            (t.title != null && t.title.toLowerCase().contains(q))
        ).collect(Collectors.toList());
    }

    /**
     * Handles the low-level OS process of piping ffmpeg data to Snapcast.
     */
    private void startFfmpegStream(String filePath, String pipePath) {
        // Kill any existing music first
        stopMusic();

        try {
            // ffmpeg flags explained:
            // -re : Read input at native frame rate (CRITICAL for streaming so we don't instantly flood the pipe)
            // -i : Input file
            // -f s16le -ar 48000 -ac 2 : Format to standard Snapcast raw PCM (16-bit, 48kHz, Stereo)
            // -y : Overwrite output without asking
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
            
            // Discard standard output/error so the Java buffers don't get clogged and crash the process
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            
            currentFfmpegProcess = pb.start();
            System.out.println("[+] Playing via ffmpeg -> " + pipePath);
            
        } catch (Exception e) {
            System.err.println("[-] Failed to start ffmpeg: " + e.getMessage());
        }
    }
}