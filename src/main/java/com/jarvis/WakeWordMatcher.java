package com.jarvis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a transcription was addressed to the assistant and, if so, returns
 * the command that follows the wake word.
 *
 * The nodes stream everything said in the room, so most transcriptions are ordinary
 * conversation. The wake word therefore only counts near the start of the utterance
 * ("jarvis kill the music", "hey jarvis play jazz") — a trigger word in the middle of
 * a sentence ("we just played mario kart") is ignored.
 */
public class WakeWordMatcher {

    // Words people say before a name ("hey jarvis", "okay so jarvis", "excuse me jarvis").
    // Up to MAX_LEADING_WORDS of them may precede the wake word; anything else may not, so
    // "i told george to…" doesn't wake on the "george" mishearing. Includes how Vosk
    // actually transcribes some of them (checked with TTS): yo -> "your"/"yeah",
    // um/hmm -> "i'm"/"him", uh/ayo -> "a", ah -> "i'll"/"our", oi -> "or", sup -> "sub",
    // alright -> "right".
    private static final String[] STARTERS = {
        "hey", "hi", "hello", "yo", "ayo", "ay", "oi", "sup", "oh", "ah", "uh", "um", "hmm", "okay", "ok",
        "so", "alright", "all", "right", "well", "yeah", "please", "listen", "excuse", "me", "there", "dear",
        "a", "o", "i", "i'm", "i'll", "him", "our", "or", "your", "sub",
    };
    private static final int MAX_LEADING_WORDS = 3;

    private static final String[] PREFIX_COMMANDS = { "good morning", "good night", "goodnight" };

    private final Pattern leadingWake;
    private final Pattern anywhereTrigger;
    private final Pattern alternatePhrases;

    public WakeWordMatcher(String[] triggers) {
        String alternation = String.join("|", quoteAll(triggers));
        String starters = String.join("|", quoteAll(STARTERS));
        this.leadingWake = Pattern.compile(
                "^(?:(?:" + starters + ")\\s+){0," + MAX_LEADING_WORDS + "}?(?:" + alternation + ")(?:\\s+|$)");
        this.anywhereTrigger = Pattern.compile("\\b(?:" + alternation + ") \\b");

        StringBuilder overrides = new StringBuilder();
        for (String trigger : quoteAll(triggers)) {
            for (String prefix : PREFIX_COMMANDS) {
                overrides.append('|').append(prefix).append(' ').append(trigger);
            }
        }
        // Self-contained phrases that act as their own wake word, wherever they appear
        this.alternatePhrases = Pattern.compile("\\b(?:wake up daddy's home|wake up daddy home|we got daddy's home"
                + "|we got daddy home|we've got daddy's home|we've got daddy home|coachella music"
                + overrides + ")\\b");
    }

    private static String[] quoteAll(String[] words) {
        String[] quoted = new String[words.length];
        for (int i = 0; i < words.length; i++) quoted[i] = Pattern.quote(words[i]);
        return quoted;
    }

    /** Returns the command text if the utterance is addressed to us, otherwise null. */
    public String extractCommand(String transcript) {
        if (transcript == null) return null;
        String text = transcript.toLowerCase().trim();
        if (text.isEmpty()) return null;

        Matcher m = leadingWake.matcher(text);
        if (m.find()) {
            String command = text.substring(m.end()).trim();
            return command.isEmpty() ? null : command;
        }
        if (alternatePhrases.matcher(text).find()) {
            return anywhereTrigger.matcher(text).replaceAll("").trim();
        }
        return null;
    }

    /** Vosk transcriptions that stand in for each supported wake word. */
    public static String[] triggersFor(String wakeWord) {
        switch (wakeWord) {
            case "echo":
                return new String[] { "echo", "ago", "go", "i go", "i can", "psycho" };
            case "alexa":
                return new String[] { "alexa", "lecture", "a lecture", "lecturn", "a lecturn", "elixir" };
            case "mycroft":
                return new String[] { "mycroft", "microsoft", "my craft", "my draft", "my grabbed", "my dropped",
                        "my shrugged", "my grab", "mike robbed" };
            case "ineffa":
                // "Ineffa" isn't in the Vosk vocabulary, so it comes out as similar-sounding
                // words. Variants below were collected by running synthesized speech of each
                // pronunciation through the model; longer phrases first.
                return new String[] {
                        "ineffa",
                        // ee-neff-uh (preferred)
                        "he never", "enough for", "enough off", "enough on", "enough i", "innova",
                        "in a fact", "in a far", "ina far", "ina ah", "in a fuck", "it never",
                        // eye-neff-uh
                        "i have never", "i have ever", "i an effort", "i never",
                        // ih-neff-uh
                        "i each never", "each never", "i h never", "i'd never", "i've never",
                        "and never", "in never", "in ever", "in africa", "in alpha", "in a for", "in for" };
            case "cortana":
                return new String[] { "cortana", "cortona", "core kana", "court on a", "court on", "quarter",
                        "we're gonna", "corker" };
            default: // "jarvis"
                // Removed "your", "your this", "this", "just", "job", "john": they open too
                // many ordinary sentences ("just like shooting where they're at") to be
                // safe as a wake word, even when anchored to the start.
                return new String[] { "jarvis", "jervis", "darvish", "dervish", "dervis", "garbage",
                        "jurgen", "drivers", "travis", "harvest", "journalist", "german", "germans",
                        "georgia", "george", "jarred", "target", "judge", "jerk", "jerks", "service",
                        "dravid", "turkish", "josh", "drugs" };
        }
    }
}
