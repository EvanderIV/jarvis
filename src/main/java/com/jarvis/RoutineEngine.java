package com.jarvis;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RoutineEngine {

    private final ScheduledExecutorService scheduler;
    private final LmsController lmsController;
    private final MusicManager musicManager;

    public RoutineEngine(LmsController lmsController, MusicManager musicManager) {
        this.scheduler = Executors.newScheduledThreadPool(5);
        this.lmsController = lmsController;
        this.musicManager = musicManager;
    }

    private static final int DEFAULT_VOLUME_FALLBACK = 60;

    /**
     * Returns seconds until the next occurrence of targetTime on any of the allowedDays.
     * Searches up to 8 days out so it always finds a result for any non-empty day set.
     */
    private long calculateDelayUntilNext(LocalTime targetTime, Set<DayOfWeek> allowedDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime candidate = now.with(targetTime);
        for (int i = 0; i < 8; i++) {
            if (allowedDays.contains(candidate.getDayOfWeek()) && candidate.isAfter(now)) {
                return Duration.between(now, candidate).getSeconds();
            }
            candidate = candidate.plusDays(1);
        }
        // Fallback — should never be reached with a non-empty allowedDays
        return Duration.between(now, now.with(targetTime).plusDays(1)).getSeconds();
    }

    public void fadeVolume(List<String> targetMacs, double startRatio, double endRatio, int durationSeconds) {
        int steps = durationSeconds * 2;
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

    // Stored as a flat pair so RoutineBuilder (a non-static inner class) can hold them
    // without requiring Java 16+ static members in inner classes.
    private static class RoutineStep {
        final long delayFromTrigger;
        final Runnable action;

        RoutineStep(long delayFromTrigger, Runnable action) {
            this.delayFromTrigger = delayFromTrigger;
            this.action = action;
        }
    }

    public RoutineBuilder createRoutine(List<String> targetMacs) {
        return new RoutineBuilder(targetMacs);
    }

    public class RoutineBuilder {
        private final List<String> targetMacs;
        private int triggerHour = 8, triggerMinute = 0;
        private Set<DayOfWeek> allowedDays = EnumSet.allOf(DayOfWeek.class); // every day by default
        private long accumulatedDelaySeconds = 0;
        private final List<RoutineStep> steps = new ArrayList<>();

        public RoutineBuilder(List<String> targetMacs) {
            this.targetMacs = targetMacs;
        }

        /**
         * Limits this routine to specific days of the week.
         * Letters: M=Mon T=Tue W=Wed R=Thu F=Fri S=Sat N=Sun
         * Examples: "MTWRF" (weekdays), "SN" (weekend), "MWF"
         */
        public RoutineBuilder onDays(String dayString) {
            allowedDays = EnumSet.noneOf(DayOfWeek.class);
            for (char c : dayString.toUpperCase().toCharArray()) {
                switch (c) {
                    case 'M': allowedDays.add(DayOfWeek.MONDAY);    break;
                    case 'T': allowedDays.add(DayOfWeek.TUESDAY);   break;
                    case 'W': allowedDays.add(DayOfWeek.WEDNESDAY); break;
                    case 'R': allowedDays.add(DayOfWeek.THURSDAY);  break;
                    case 'F': allowedDays.add(DayOfWeek.FRIDAY);    break;
                    case 'S': allowedDays.add(DayOfWeek.SATURDAY);  break;
                    case 'N': allowedDays.add(DayOfWeek.SUNDAY);    break;
                }
            }
            return this;
        }

        public RoutineBuilder triggerAtTime(int hour, int minute) {
            this.triggerHour = hour;
            this.triggerMinute = minute;
            return this;
        }

        public RoutineBuilder waitMinutes(double minutes) {
            this.accumulatedDelaySeconds += (long) (minutes * 60.0);
            return this;
        }

        public RoutineBuilder setVolumeRatio(double ratio) {
            final long stepDelay = accumulatedDelaySeconds;
            steps.add(new RoutineStep(stepDelay, () -> {
                System.out.println("[*] Routine: Setting volume ratio " + ratio + " per speaker");
                for (String mac : targetMacs) {
                    int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
                    lmsController.setVolume(List.of(mac), (int) Math.round(ratio * speakerMax));
                }
            }));
            return this;
        }

        public RoutineBuilder fadeVolumeRatio(double startRatio, double endRatio, int durationSeconds) {
            final long stepDelay = accumulatedDelaySeconds;
            accumulatedDelaySeconds += durationSeconds;
            steps.add(new RoutineStep(stepDelay, () -> {
                System.out.println("[*] Routine: Fading volume from ratio " + startRatio + " to " + endRatio);
                fadeVolume(targetMacs, startRatio, endRatio, durationSeconds);
            }));
            return this;
        }

        public RoutineBuilder pausePlayback() {
            final long stepDelay = accumulatedDelaySeconds;
            steps.add(new RoutineStep(stepDelay, () -> {
                musicManager.stopMusic();
                System.out.println("[*] Routine: Paused playback.");
            }));
            return this;
        }

        public RoutineBuilder playTheme(String[] tags) {
            return playTheme(tags, 0, 4);
        }

        public RoutineBuilder playTheme(String[] tags, int minSpeed, int maxSpeed) {
            String themeName = String.join(" ", tags);
            final long stepDelay = accumulatedDelaySeconds;
            steps.add(new RoutineStep(stepDelay, () -> {
                System.out.println("[*] Routine: Switching to theme '" + themeName + "'...");
                musicManager.switchTheme(themeName, targetMacs, minSpeed, maxSpeed);
            }));
            return this;
        }

        public void triggerNow() {
            System.out.printf("[+] Routine triggered immediately (%d steps).%n", steps.size());
            for (RoutineStep step : steps) {
                scheduler.schedule(step.action, step.delayFromTrigger, TimeUnit.SECONDS);
            }
        }

        public void build() {
            if (allowedDays.isEmpty()) {
                System.out.println("[-] Routine has no allowed days — skipping schedule.");
                return;
            }
            System.out.printf("[+] Routine compiled (%d steps, trigger %02d:%02d, days %s). Scheduling...%n",
                    steps.size(), triggerHour, triggerMinute, allowedDays);
            scheduleNextRun();
        }

        private void scheduleNextRun() {
            long delayUntilTrigger = calculateDelayUntilNext(
                    LocalTime.of(triggerHour, triggerMinute), allowedDays);

            long maxStepDelay = steps.stream().mapToLong(s -> s.delayFromTrigger).max().orElse(0);

            for (RoutineStep step : steps) {
                long totalDelay = delayUntilTrigger + step.delayFromTrigger;
                scheduler.schedule(step.action, totalDelay, TimeUnit.SECONDS);
            }

            // Reschedule for the next valid day once the last step finishes (60s buffer)
            long nextRunDelay = delayUntilTrigger + maxStepDelay + 60;
            scheduler.schedule(this::scheduleNextRun, nextRunDelay, TimeUnit.SECONDS);

            System.out.printf("[+] Next routine run in %dh %dm%n",
                    delayUntilTrigger / 3600, (delayUntilTrigger % 3600) / 60);
        }
    }
}
