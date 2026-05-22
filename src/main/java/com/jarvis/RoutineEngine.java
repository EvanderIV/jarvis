package com.jarvis;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RoutineEngine {

    private final ScheduledExecutorService scheduler;
    private final LmsController lmsController;
    private final MusicManager musicManager; 

    public RoutineEngine(LmsController lmsController, MusicManager musicManager) {
        // A thread pool to handle multiple simultaneous fading/scheduling tasks
        this.scheduler = Executors.newScheduledThreadPool(5);
        this.lmsController = lmsController;
        this.musicManager = musicManager;
    }

    /**
     * Calculates the delay in seconds from right now until the next occurrence of a specific time.
     */
    private long calculateDelayUntilNext(LocalTime targetTime) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.with(targetTime);

        if (now.compareTo(nextRun) >= 0) {
            // Target time has already passed today, schedule for tomorrow
            nextRun = nextRun.plusDays(1);
        }
        return Duration.between(now, nextRun).getSeconds();
    }

    private static final int DEFAULT_VOLUME_FALLBACK = 60;

    /**
     * Executes a smooth volume fade over a specific duration.
     * Uses RATIOS (0.0 to 1.0) multiplied by each speaker's individual default volume,
     * so speakers with different comfort levels all scale correctly.
     */
    public void fadeVolume(List<String> targetMacs, double startRatio, double endRatio, int durationSeconds) {
        int steps = durationSeconds * 2; // 2 adjustments per second for smoothness
        long delayBetweenStepsMs = 500;

        for (int i = 0; i <= steps; i++) {
            final int step = i;
            long executionDelayMs = i * delayBetweenStepsMs;

            scheduler.schedule(() -> {
                if (!musicManager.isPlaying()) return;
                double ratio = startRatio + (endRatio - startRatio) * ((double) step / steps);
                for (String mac : targetMacs) {
                    int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
                    int vol = (int) Math.round(ratio * speakerMax);
                    try {
                        lmsController.setVolume(List.of(mac), vol);
                    } catch (Exception e) {
                        System.err.println("[-] RoutineEngine Fade Error: " + e.getMessage());
                    }
                }
            }, executionDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    // ==========================================
    // THE ROUTINE BUILDER
    // ==========================================

    public RoutineBuilder createRoutine(List<String> targetMacs) {
        return new RoutineBuilder(targetMacs);
    }

    public class RoutineBuilder {
        private final List<String> targetMacs;
        private long accumulatedDelaySeconds = 0;

        public RoutineBuilder(List<String> targetMacs) {
            this.targetMacs = targetMacs;
        }

        /**
         * Sets the absolute start time for this routine (e.g., 7:00 AM).
         */
        public RoutineBuilder triggerAtTime(int hour, int minute) {
            this.accumulatedDelaySeconds = calculateDelayUntilNext(LocalTime.of(hour, minute));
            System.out.println("[+] Scheduled routine to start at " + String.format("%02d:%02d", hour, minute));
            return this;
        }

        /**
         * Adds a delay relative to the previous step in the routine.
         */
        public RoutineBuilder waitMinutes(double minutes) {
            this.accumulatedDelaySeconds += (long) (minutes * 60.0);
            return this;
        }

        public RoutineBuilder setVolumeRatio(double ratio) {
            final long executionTime = accumulatedDelaySeconds;

            scheduler.schedule(() -> {
                System.out.println("[*] Routine: Setting volume ratio " + ratio + " per speaker");
                for (String mac : targetMacs) {
                    int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
                    lmsController.setVolume(List.of(mac), (int) Math.round(ratio * speakerMax));
                }
            }, executionTime, TimeUnit.SECONDS);

            return this;
        }

        public RoutineBuilder fadeVolumeRatio(double startRatio, double endRatio, int durationSeconds) {
            final long executionTime = accumulatedDelaySeconds;
            accumulatedDelaySeconds += durationSeconds;

            scheduler.schedule(() -> {
                System.out.println("[*] Routine: Fading volume from ratio " + startRatio + " to " + endRatio);
                fadeVolume(targetMacs, startRatio, endRatio, durationSeconds);
            }, executionTime, TimeUnit.SECONDS);

            return this;
        }

        public RoutineBuilder pausePlayback() {
            final long executionTime = accumulatedDelaySeconds;
            scheduler.schedule(() -> {
                // Route this through MusicManager to ensure the continuous playback thread is cleanly killed!
                musicManager.stopMusic(); 
                System.out.println("[*] Routine: Paused playback.");
            }, executionTime, TimeUnit.SECONDS);
            return this;
        }

        /**
         * Hooks into the new MusicManager Theme system (e.g., "wakeup", "lofi", "active")
         */
        public RoutineBuilder playTheme(String themeName) {
            final long executionTime = accumulatedDelaySeconds;
            
            scheduler.schedule(() -> {
                System.out.println("[*] Routine: Switching to theme '" + themeName + "'...");
                musicManager.switchTheme(themeName, targetMacs);
            }, executionTime, TimeUnit.SECONDS);
            
            return this;
        }

        public void build() {
            System.out.println("[+] Routine successfully compiled and handed to the scheduler.");
        }
    }
}