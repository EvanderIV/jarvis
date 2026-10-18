package com.jarvis;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RoutineEngine {

    private final ScheduledExecutorService scheduler;
    private final LmsController lmsController;
    private final MusicManager musicManager;

    // Runs that have been triggered and still have steps pending. "Kill the music"
    // cancels these; the daily schedule that spawns them is left alone.
    private final Set<RoutineRun> activeRuns = ConcurrentHashMap.newKeySet();

    public RoutineEngine(LmsController lmsController, MusicManager musicManager) {
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.lmsController = lmsController;
        this.musicManager = musicManager;
    }

    private static final int DEFAULT_VOLUME_FALLBACK = 60;
    private static final long FADE_TICK_MS = 500;

    /** One triggered execution of a routine; owns every future it scheduled. */
    private static class RoutineRun {
        private final List<ScheduledFuture<?>> futures = new ArrayList<>();
        private volatile boolean cancelled = false;

        synchronized void track(ScheduledFuture<?> future) {
            futures.add(future);
            if (cancelled) future.cancel(false);
        }

        synchronized void cancel() {
            cancelled = true;
            for (ScheduledFuture<?> f : futures) f.cancel(false);
        }
    }

    /**
     * Cancels every routine currently in progress (pending theme switches, fades,
     * pauses). Scheduled future runs — e.g. tomorrow's wake-up — still happen.
     * Returns how many runs were cancelled.
     */
    public int cancelActiveRoutines() {
        int count = 0;
        for (RoutineRun run : activeRuns) {
            run.cancel();
            count++;
        }
        activeRuns.clear();
        if (count > 0) {
            System.out.println("[*] RoutineEngine: Cancelled " + count + " active routine(s).");
        }
        return count;
    }

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

    /**
     * Fades each speaker from startRatio to endRatio of its default volume. Runs as a
     * single repeating task and only sends a command when a speaker's integer volume
     * actually changes, so long fades stay cheap.
     */
    private void fadeVolume(List<String> targetMacs, double startRatio, double endRatio, int durationSeconds,
            RoutineRun run) {
        final long startNanos = System.nanoTime();
        final long durationNanos = TimeUnit.SECONDS.toNanos(Math.max(0, durationSeconds));
        final Map<String, Integer> lastSent = new HashMap<>();
        final ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];

        Runnable tick = () -> {
            double progress = durationNanos == 0 ? 1.0
                    : Math.min(1.0, (System.nanoTime() - startNanos) / (double) durationNanos);
            if (musicManager.isPlaying()) {
                double ratio = startRatio + (endRatio - startRatio) * progress;
                for (String mac : targetMacs) {
                    int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
                    int vol = (int) Math.round(ratio * speakerMax);
                    if (!Integer.valueOf(vol).equals(lastSent.get(mac))) {
                        try {
                            lmsController.setVolume(List.of(mac), vol);
                            lastSent.put(mac, vol);
                        } catch (Exception e) {
                            System.err.println("[-] RoutineEngine Fade Error: " + e.getMessage());
                        }
                    }
                }
            }
            if (progress >= 1.0) {
                synchronized (self) {
                    if (self[0] != null) self[0].cancel(false);
                }
            }
        };

        synchronized (self) {
            self[0] = scheduler.scheduleAtFixedRate(tick, 0, FADE_TICK_MS, TimeUnit.MILLISECONDS);
        }
        run.track(self[0]);
    }

    // Stored as a flat pair so RoutineBuilder (a non-static inner class) can hold them
    // without requiring Java 16+ static members in inner classes.
    private static class RoutineStep {
        final long delayFromTrigger;
        final long durationSeconds; // how long the step keeps running after it starts (fades)
        final Consumer<RoutineRun> action;

        RoutineStep(long delayFromTrigger, long durationSeconds, Consumer<RoutineRun> action) {
            this.delayFromTrigger = delayFromTrigger;
            this.durationSeconds = durationSeconds;
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
            steps.add(new RoutineStep(accumulatedDelaySeconds, 0, run -> {
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
            steps.add(new RoutineStep(stepDelay, durationSeconds, run -> {
                System.out.println("[*] Routine: Fading volume from ratio " + startRatio + " to " + endRatio);
                fadeVolume(targetMacs, startRatio, endRatio, durationSeconds, run);
            }));
            return this;
        }

        public RoutineBuilder pausePlayback() {
            steps.add(new RoutineStep(accumulatedDelaySeconds, 0, run -> {
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
            steps.add(new RoutineStep(accumulatedDelaySeconds, 0, run -> {
                System.out.println("[*] Routine: Switching to theme '" + themeName + "'...");
                musicManager.switchTheme(themeName, targetMacs, minSpeed, maxSpeed);
            }));
            return this;
        }

        public void triggerNow() {
            System.out.printf("[+] Routine triggered immediately (%d steps).%n", steps.size());
            startRun();
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

        private void startRun() {
            RoutineRun run = new RoutineRun();
            activeRuns.add(run);
            long lastStepEnds = 0;
            for (RoutineStep step : steps) {
                run.track(scheduler.schedule(() -> {
                    if (run.cancelled) return;
                    try {
                        step.action.accept(run);
                    } catch (Exception e) {
                        System.err.println("[-] Routine step failed: " + e.getMessage());
                    }
                }, step.delayFromTrigger, TimeUnit.SECONDS));
                lastStepEnds = Math.max(lastStepEnds, step.delayFromTrigger + step.durationSeconds);
            }
            run.track(scheduler.schedule(() -> { activeRuns.remove(run); }, lastStepEnds + 1, TimeUnit.SECONDS));
        }

        private void scheduleNextRun() {
            long delayUntilTrigger = calculateDelayUntilNext(
                    LocalTime.of(triggerHour, triggerMinute), allowedDays);

            scheduler.schedule(() -> {
                System.out.printf("[+] Routine %02d:%02d starting (%d steps).%n", triggerHour, triggerMinute, steps.size());
                startRun();
                // Pick the next valid day once we're safely past this trigger minute.
                scheduler.schedule(this::scheduleNextRun, 61, TimeUnit.SECONDS);
            }, delayUntilTrigger, TimeUnit.SECONDS);

            System.out.printf("[+] Next routine run in %dh %dm%n",
                    delayUntilTrigger / 3600, (delayUntilTrigger % 3600) / 60);
        }
    }
}
