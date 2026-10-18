package com.jarvis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline batch tool that pre-computes a fadeout timestamp for every track listed
 * in music.json and writes it back into the file as the "fadeoutTimestamp" field.
 *
 * <p>This replaces the old runtime analysis that ran at the start of every track and
 * starved LMS's audio stream (the "rewind 2s every 2s" buffer underruns). At playback
 * time {@link MusicManager} now simply reads the pre-computed value — zero decode work.
 *
 * <p>Only tracks already listed in music.json are analyzed, so the fadeout point is
 * stored as song metadata alongside the existing genres/moods/stats.
 *
 * <p>Runs on Linux or Windows. Only requirement is {@code ffmpeg} on PATH, and only
 * for compressed formats (mp3/flac/m4a); native WAV/AIFF/AU are read without it.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -cp target/jarvis-1.0-SNAPSHOT.jar com.jarvis.FadeoutIndexer [pathToMusicDirOrJson] [--force] [--debug]
 * </pre>
 * <ul>
 *   <li>{@code pathToMusicDirOrJson} — the music folder OR a direct path to music.json.
 *       If omitted, uses the {@code JARVIS_MUSIC_DIR} env var, then the Linux defaults.</li>
 *   <li>{@code --force} — re-analyze even tracks that already have a fadeoutTimestamp.</li>
 *   <li>{@code --debug} — verbose AudioAnalyzer output.</li>
 * </ul>
 *
 * <p>Windows example:
 * {@code java -cp target\jarvis-1.0-SNAPSHOT.jar com.jarvis.FadeoutIndexer "C:\Users\evanm\Music\jarvis-music"}
 * <br>Linux example:
 * {@code java -cp target/jarvis-1.0-SNAPSHOT.jar com.jarvis.FadeoutIndexer /home/evanm/Music/jarvis-music}
 */
public class FadeoutIndexer {

    private static final String INDEX_FILE = "music.json";

    public static void main(String[] args) {
        boolean force = false;
        String pathArg = null;
        for (String a : args) {
            if (a.equals("--force")) {
                force = true;
            } else if (a.equals("--debug")) {
                App.DEBUG_MODE = true;
            } else if (!a.startsWith("--")) {
                pathArg = a;
            }
        }

        Path jsonPath = resolveJsonPath(pathArg);
        if (jsonPath == null) {
            System.err.println("[-] Could not locate music.json.");
            System.err.println("    Pass the music folder (or music.json) as the first argument, e.g.:");
            System.err.println("    Windows: java -cp jarvis.jar com.jarvis.FadeoutIndexer \"C:\\Users\\evanm\\Music\\jarvis-music\"");
            System.err.println("    Linux:   java -cp jarvis.jar com.jarvis.FadeoutIndexer /home/evanm/Music/jarvis-music");
            System.exit(1);
            return;
        }
        Path jsonDir = jsonPath.toAbsolutePath().getParent();
        // The audio files live in a "jarvis-music" subdirectory next to music.json —
        // music.json sits one level ABOVE the music. Prefer that subdir, but fall back
        // to music.json's own directory if the file happens to sit alongside the index.
        List<Path> audioBases = new ArrayList<>();
        Path jarvisMusic = jsonDir.resolve("jarvis-music");
        if (Files.isDirectory(jarvisMusic)) {
            audioBases.add(jarvisMusic);
        }
        audioBases.add(jsonDir);

        System.out.println("[+] FadeoutIndexer");
        System.out.println("    Index file : " + jsonPath.toAbsolutePath());
        System.out.println("    Audio dir  : " + audioBases.get(0));
        System.out.println("    Force      : " + force);
        System.out.println("    OS         : " + System.getProperty("os.name"));
        System.out.println();

        Gson gson = new Gson();
        JsonObject root;
        try {
            String json = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
            root = gson.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            System.err.println("[-] Failed to read " + jsonPath + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        if (root == null || !root.has("music") || !root.get("music").isJsonArray()) {
            System.err.println("[-] music.json has no 'music' array.");
            System.exit(1);
            return;
        }

        JsonArray tracks = root.getAsJsonArray("music");
        int total = tracks.size();
        int analyzed = 0, noFade = 0, skipped = 0, missing = 0, failed = 0;

        System.out.println("[+] " + total + " tracks in index.\n");

        for (int i = 0; i < total; i++) {
            JsonElement el = tracks.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject track = el.getAsJsonObject();

            String fileRel = track.has("file") && !track.get("file").isJsonNull()
                    ? track.get("file").getAsString() : null;
            String title = track.has("title") && !track.get("title").isJsonNull()
                    ? track.get("title").getAsString() : fileRel;
            String progress = "[" + (i + 1) + "/" + total + "] ";

            if (fileRel == null || fileRel.isEmpty()) {
                System.out.println(progress + "(no 'file' field) — skipping");
                continue;
            }

            boolean alreadyDone = track.has("fadeoutTimestamp") && !track.get("fadeoutTimestamp").isJsonNull();
            if (alreadyDone && !force) {
                skipped++;
                continue;
            }

            File audio = null;
            for (Path base : audioBases) {
                File cand = base.resolve(fileRel).toFile();
                if (cand.exists()) {
                    audio = cand;
                    break;
                }
            }
            if (audio == null) {
                System.out.println(progress + "MISSING on disk: " + fileRel);
                missing++;
                continue;
            }

            System.out.println(progress + "Analyzing: " + title);
            try {
                AudioAnalyzer.FadeoutInfo info = AudioAnalyzer.analyzeAudioWithConversion(audio.getAbsolutePath());
                float ts = (info != null) ? info.fadeoutTimestamp : -1f;
                track.addProperty("fadeoutTimestamp", ts);
                if (info != null) {
                    System.out.println("        fadeout @ " + String.format("%.1f", ts) + "s"
                            + "  (duration " + String.format("%.1f", info.totalDuration) + "s)");
                    analyzed++;
                } else {
                    System.out.println("        no fadeout detected (stored -1)");
                    noFade++;
                }
            } catch (Exception e) {
                System.out.println("        [-] analysis error: " + e.getMessage());
                failed++;
            }
        }

        // Back up the original, then write the updated index.
        try {
            Path backup = jsonPath.resolveSibling(INDEX_FILE + ".bak");
            Files.copy(jsonPath, backup, StandardCopyOption.REPLACE_EXISTING);

            Gson pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.write(jsonPath, pretty.toJson(root).getBytes(StandardCharsets.UTF_8));
            System.out.println("\n[+] Wrote " + jsonPath.getFileName()
                    + "  (backup: " + backup.getFileName() + ")");
        } catch (Exception e) {
            System.err.println("\n[-] Failed to write index: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("\n[+] Done."
                + "  analyzed=" + analyzed
                + "  noFadeout=" + noFade
                + "  skipped(existing)=" + skipped
                + "  missing=" + missing
                + "  failed=" + failed);
    }

    /**
     * Resolves the music.json path from (in priority order): the CLI argument, the
     * {@code JARVIS_MUSIC_DIR} environment variable, then the Linux defaults that
     * {@link MusicManager} uses. Each candidate may be a directory (containing
     * music.json) or a direct path to a .json file.
     */
    private static Path resolveJsonPath(String pathArg) {
        List<String> candidates = new ArrayList<>();
        if (pathArg != null) {
            candidates.add(pathArg);
        }
        String env = System.getenv("JARVIS_MUSIC_DIR");
        if (env != null && !env.trim().isEmpty()) {
            candidates.add(env.trim());
        }
        candidates.add("/home/evanm/Music/jarvis-music");
        candidates.add("/home/evanm/Music");

        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".json")) {
                return p;
            }
            Path asDir = p.resolve(INDEX_FILE);
            if (Files.isRegularFile(asDir)) {
                return asDir;
            }
        }
        return null;
    }
}
