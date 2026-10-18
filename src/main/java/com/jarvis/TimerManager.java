package com.jarvis;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Countdown timers and clock alarms.
 * A timer plays a short chime through LMS; an alarm starts the "wakeup" music theme.
 * Timers live in memory only and are lost if the server restarts.
 */
public class TimerManager {

    public enum Kind { TIMER, ALARM }

    // Override with JARVIS_TIMER_SOUND=/path/to/sound.mp3 (must be readable by the LMS user).
    // Otherwise a beep WAV is generated in the working directory on first use.
    private static final String GENERATED_CHIME = "sounds/timer-chime.wav";
    private static final int DEFAULT_VOLUME_FALLBACK = 60;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final LmsController lmsController;
    private final MusicManager musicManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimerManager");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Entry> active = new ConcurrentHashMap<>();

    private static final class Entry {
        final int id;
        final Kind kind;
        final LocalDateTime firesAt;
        volatile ScheduledFuture<?> future;
        Entry(int id, Kind kind, LocalDateTime firesAt) { this.id = id; this.kind = kind; this.firesAt = firesAt; }
    }

    public TimerManager(LmsController lmsController, MusicManager musicManager) {
        this.lmsController = lmsController;
        this.musicManager = musicManager;
    }

    /** Starts a countdown. Returns a human-readable confirmation. */
    public String startTimer(int seconds) {
        LocalDateTime firesAt = LocalDateTime.now().plusSeconds(seconds);
        schedule(Kind.TIMER, firesAt, seconds * 1000L);
        return "Timer set for " + TimeExpressionParser.describeDuration(seconds)
                + " (rings at " + firesAt.format(CLOCK) + ")";
    }

    /** Sets an alarm for an absolute local time. Returns a human-readable confirmation. */
    public String setAlarm(LocalDateTime when) {
        long delayMs = Duration.between(LocalDateTime.now(), when).toMillis();
        if (delayMs <= 0) {
            return null;
        }
        schedule(Kind.ALARM, when, delayMs);
        boolean today = when.toLocalDate().equals(LocalDateTime.now().toLocalDate());
        return "Alarm set for " + when.format(CLOCK) + (today ? " today" : " " + when.getDayOfWeek().toString().toLowerCase());
    }

    private void schedule(Kind kind, LocalDateTime firesAt, long delayMs) {
        Entry entry = new Entry(nextId.getAndIncrement(), kind, firesAt);
        active.put(entry.id, entry);
        entry.future = scheduler.schedule(() -> fire(entry), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels pending timers/alarms of the given kind (null = both). Returns how many were cancelled. */
    public int cancel(Kind kind) {
        int count = 0;
        for (Entry entry : new ArrayList<>(active.values())) {
            if (kind == null || entry.kind == kind) {
                entry.future.cancel(false);
                active.remove(entry.id);
                count++;
            }
        }
        return count;
    }

    /** One line per pending timer/alarm, soonest first. */
    public List<String> describeActive() {
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort((a, b) -> a.firesAt.compareTo(b.firesAt));
        List<String> lines = new ArrayList<>();
        for (Entry e : entries) {
            lines.add(String.format("#%d %s at %s", e.id, e.kind.toString().toLowerCase(), e.firesAt.format(CLOCK)));
        }
        return lines;
    }

    private void fire(Entry entry) {
        active.remove(entry.id);
        try {
            if (entry.kind == Kind.ALARM) {
                System.out.println("[+] Alarm #" + entry.id + " ringing — starting wake-up music.");
                List<String> speakers = lmsController.getAllRegisteredSpeakers();
                musicManager.playMusic("wakeup", speakers);
                setSpeakersToRatio(speakers, 0.7);
            } else {
                System.out.println("[+] Timer #" + entry.id + " finished.");
                String sound = resolveChimePath();
                if (sound == null) {
                    System.err.println("[-] TimerManager: No timer sound available; set JARVIS_TIMER_SOUND.");
                    return;
                }
                List<String> speakers = lmsController.getAllRegisteredSpeakers();
                // Only touch volume when nothing is playing, so a chime over music
                // doesn't leave the music at a different level afterwards.
                if (!musicManager.isPlaying()) {
                    lmsController.unmute(speakers);
                    setSpeakersToRatio(speakers, 0.7);
                }
                // If music is in continuous mode, the playback monitor resumes the
                // theme with the next track once the chime ends.
                lmsController.playFile(speakers, sound);
            }
        } catch (Exception e) {
            System.err.println("[-] TimerManager: Failed to ring " + entry.kind + " #" + entry.id + ": " + e.getMessage());
        }
    }

    private void setSpeakersToRatio(List<String> speakers, double ratio) {
        for (String mac : speakers) {
            int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
            lmsController.setVolume(List.of(mac), (int) Math.round(ratio * speakerMax));
        }
    }

    private String resolveChimePath() {
        String configured = System.getenv("JARVIS_TIMER_SOUND");
        if (configured != null && !configured.trim().isEmpty()) {
            Path p = Paths.get(configured.trim());
            if (Files.isReadable(p)) return p.toAbsolutePath().toString();
            System.err.println("[-] TimerManager: JARVIS_TIMER_SOUND not readable: " + configured);
        }
        Path generated = Paths.get(GENERATED_CHIME).toAbsolutePath();
        try {
            if (!Files.exists(generated)) {
                Files.createDirectories(generated.getParent());
                Files.write(generated, buildChimeWav());
                System.out.println("[+] TimerManager: Generated timer chime at " + generated);
            }
            return generated.toString();
        } catch (Exception e) {
            System.err.println("[-] TimerManager: Could not generate chime: " + e.getMessage());
            return null;
        }
    }

    /** Three pairs of 880 Hz beeps, 44.1 kHz 16-bit mono WAV. */
    static byte[] buildChimeWav() {
        int sampleRate = 44100;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int rep = 0; rep < 3; rep++) {
            appendTone(pcm, sampleRate, 880, 0.18);
            appendTone(pcm, sampleRate, 0, 0.08);
            appendTone(pcm, sampleRate, 880, 0.18);
            appendTone(pcm, sampleRate, 0, 0.6);
        }
        byte[] data = pcm.toByteArray();

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.length).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes()).putInt(data.length);

        byte[] wav = new byte[44 + data.length];
        System.arraycopy(header.array(), 0, wav, 0, 44);
        System.arraycopy(data, 0, wav, 44, data.length);
        return wav;
    }

    private static void appendTone(ByteArrayOutputStream out, int sampleRate, double freq, double seconds) {
        int samples = (int) (sampleRate * seconds);
        int fade = Math.min(samples / 2, sampleRate / 100); // 10 ms ramps to avoid clicks
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples - 1 - i) / (double) Math.max(1, fade));
            short s = (short) (freq == 0 ? 0 : Math.sin(2 * Math.PI * freq * i / sampleRate) * 12000 * envelope);
            out.write(s & 0xFF);
            out.write((s >> 8) & 0xFF);
        }
    }
}
