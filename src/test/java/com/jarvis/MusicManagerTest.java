package com.jarvis;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
