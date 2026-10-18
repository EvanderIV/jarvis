package com.jarvis;

import java.util.List;

/** Routine step sequences shared between scheduled and voice-triggered routines. */
public final class Routines {

    private Routines() {}

    public static final int NAP_ASMR_MINUTES = 25;
    public static final int NAP_FADE_MINUTES = 5;

    /**
     * The standard wake-up sequence used on weekday mornings: silence, wake-up tracks
     * fading in, then progressively more upbeat themes. Appended to whatever the
     * builder already has, so it can follow a delay.
     */
    public static RoutineEngine.RoutineBuilder appendWakeupProtocol(RoutineEngine.RoutineBuilder routine) {
        return routine
            .setVolumeRatio(0.0)
            .playTheme(new String[] { "+Wakeup", "-Somber" }, 0, 3)
            .fadeVolumeRatio(0.0, 0.25, 30)
            .playTheme(new String[] { "+Relaxing", "Uplifting", "Happy", "-Epic", "-Wagakki", "-Somber", "-Meme", "-Profanity", "-Wakeup" }, 1, 2)
            .fadeVolumeRatio(0.25, 1.0, 150)
            .waitMinutes(2.5)
            .playTheme(new String[] { "Relaxing", "-Epic", "-Somber", "-Meme", "-Profanity", "-Wakeup" }, 1, 2)
            .waitMinutes(6)
            .fadeVolumeRatio(1.0, 0.7, 120)
            .playTheme(new String[] { "+Upbeat", "-Epic", "-Somber", "-Meme", "-Profanity", "-Wakeup" }, 2, 3);
    }

    /**
     * Nap: random ASMR on the bedroom speakers for 25 minutes, a 5-minute fade to
     * silence, then the standard wake-up protocol on every speaker.
     * Runs as two routines so each half targets its own speakers; "kill the music"
     * cancels both, including the pending wake-up.
     */
    public static void startNap(RoutineEngine engine, List<String> bedroomSpeakers, List<String> allSpeakers) {
        engine.createRoutine(bedroomSpeakers)
            .setVolumeRatio(0.5)
            .playTheme(new String[] { "+ASMR" })
            .waitMinutes(NAP_ASMR_MINUTES)
            .fadeVolumeRatio(0.5, 0.0, NAP_FADE_MINUTES * 60)
            .pausePlayback()
            .triggerNow();

        // A few seconds' gap guarantees the ASMR has stopped (and the bedroom-only
        // restriction is lifted) before the wake-up starts from silence.
        appendWakeupProtocol(engine.createRoutine(allSpeakers)
                .waitMinutes(NAP_ASMR_MINUTES + NAP_FADE_MINUTES)
                .waitSeconds(5))
            .triggerNow();
    }
}
