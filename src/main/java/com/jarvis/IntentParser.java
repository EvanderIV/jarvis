package com.jarvis;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.LinkedList;
import java.util.Collections;

import com.jarvis.ParsedCommand.*;

public class IntentParser {

    // --- The Lexer Dictionaries (Synonym Mapping) ---
    // Insertion-ordered so ties between equally long synonyms resolve the same way every run.
    private final Map<Action, List<String>> actionSynonyms = new LinkedHashMap<>();
    private final Map<Target, List<String>> targetSynonyms = new LinkedHashMap<>();

    // NEW: A dictionary to map spoken descriptions to canonical genres
    private final Map<String, List<String>> parameterMap = new LinkedHashMap<>();

    // --- Context & Capabilities ---
    private final LinkedList<Target> targetHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 5;
    private static final Duration HISTORY_TTL = Duration.ofMinutes(5);
    private static final Pattern REFERS_BACK = Pattern.compile("\\b(?:it|that|them|those)\\b");
    private LocalDateTime lastTargetAt = null;
    private final Map<Target, List<Action>> targetCapabilities = new LinkedHashMap<>();

    private final Clock clock;

    /**
     * Generates phrase combinations by prepending prefixes to base words
     * E.g., generatePhrasePrefixes(["jogging"], "begin the ", "initiate the ")
     * -> ["begin the jogging", "initiate the jogging"]
     */
    private List<String> generatePhrasePrefixes(List<String> baseWords, String... prefixes) {
        List<String> result = new LinkedList<>();
        for (String word : baseWords) {
            for (String prefix : prefixes) {
                result.add(prefix + word);
            }
        }
        return result;
    }

    /**
     * Detects if text is likely a timer command even with bad parses.
     * Checks for timer/minute keywords and number patterns.
     */
    private boolean likelyTimerCommand(String normalizedText) {
        String[] timerKeywords = { "timer", "countdown", "remind", "minute", "minutes", "second", "seconds", "hour",
                "hours" };
        for (String keyword : timerKeywords) {
            if (normalizedText.contains(" " + keyword)) {
                return true;
            }
        }
        return false;
    }

    // Compiled once at class load — applied in order on every parse() call.
    // Each entry is { regex, replacement }. Order matters: verb normalization
    // fires first so later phrase rules see the base form (e.g. "kill", not "killed").
    private static final Pattern[] CORRECTION_PATTERNS;
    private static final String[]  CORRECTION_REPLACEMENTS;
    static {
        String[][] rules = {
            // --- Verb normalization ---
            { "\\b(turn|play)ed\\b",                        "$1"               },
            { "\\bkill(?:ed|ing|er)\\b",                    "kill"             },

            // --- "play" phonetic corrections (Vosk mishearings) ---
            { "\\b(?:plane|display|way|why|leave|boy)\\b",       "play"             },

            // --- "play some music" mishearings ---
            { "\\b(?:playful music"
            + "|place(?:(?: and)| the)? music"
            + "|pleasure music"
            + "|please(?:(?: show))? music"
            + "|pleasant music"
            + "|pushing music"
            + "|poking music"
            + "|paid for music)\\b",                        "play some music"  },

            // --- "play active music" mishearings ---
            { "\\b(?:play acted music|question music)\\b",  "play active music"},

            // --- genre corrections ---
            { "\\b(?:a big|ethnic) music\\b",               "epic music"       },
            { "\\b(?:lacking|rock) music\\b",               "relaxing music"   },

            // --- "kill the music" mishearings ---
            { "\\b(?:gilder music"
            + "|tiller? music"
            + "|(?:still|skill) (?:the|a) music"
            + "|feel the music"
            + "|kill(?: a| the)? music"
            + "|fuel(?: a| the)? music"
            + "|kill them (?:move it|is it|it)"
            + "|kill (?:him|kilometers) (?:his iq|is it|is a)"
            + "|kill amelia"
            + "|kill a kill music"
            + "|kill (?:in|them) iraq"
            + "|give (?:the )?his it"
            + "|still needs it"
            + "|still amazing"
            + "|killed? a medic"
            + "|kill me that"
            + "|kill the news"
            + "|killing these it"
            + "|killing it"
            + "|kill me it"
            + "|(?:psycho )?killer music"
            + "|coachella(?: music)?"
            + "|go a music"
            + "|kill his iq"
            + "|kill amazing"
            + "|kill(ing)? idzik"
            + "|kill him me lick"
            + "|killing his"
            + "|killing his dick"
            + "|kill(?:ing) him (?:a )dick"
            + "|kill him using"
            + "|kilometers?"
            + "|schofield(?: music)?"
            + "|telling good luck)\\b",                     "kill the music"   },

            // --- "play meme music" mishearings ---
            { "\\bplay me(?:an)? (?:music|songs)\\b",       "play meme music"  },

            // --- popcorn mishearings ---
            { "\\bmake(?: me)?(?: some)? popcorn\\b",       "play popcorn music"},
        };

        CORRECTION_PATTERNS    = new Pattern[rules.length];
        CORRECTION_REPLACEMENTS = new String[rules.length];
        for (int i = 0; i < rules.length; i++) {
            CORRECTION_PATTERNS[i]    = Pattern.compile(rules[i][0]);
            CORRECTION_REPLACEMENTS[i] = rules[i][1];
        }
    }

    public IntentParser() {
        this(Clock.systemDefaultZone());
    }

    public IntentParser(Clock clock) {
        this.clock = clock;

        // Map all the ways a user might phrase an action
        actionSynonyms.put(Action.TURN_ON, Arrays.asList("turn on", "enable", "start", "lights on", "activate",
                "turn it on", "turn that on", "turn that thing on"));
        actionSynonyms.put(Action.TURN_OFF, Arrays.asList("turn off", "disable", "stop", "lights off", "kill",
                "turn it off", "turn that off", "turn that thing off"));
        actionSynonyms.put(Action.INCREASE_VOLUME,
                Arrays.asList("louder", "turn up", "turn it up", "crank it up", "volume up"));
        actionSynonyms.put(Action.DECREASE_VOLUME,
                Arrays.asList("quieter", "turn down", "turn it down", "crank it down", "volume down"));
        actionSynonyms.put(Action.PLAY_MUSIC, Arrays.asList("play", "put on", "crank", "listen to"));
        List<String> setTimer = Arrays.asList("set a timer", "start a timer", "countdown", "remind me in",
                "remind me to");
        actionSynonyms.put(Action.SET_TIMER, setTimer);
        actionSynonyms.put(Action.SET_ALARM, Arrays.asList("set an alarm", "set alarm", "set the alarm",
                "set a alarm", "wake me up at", "wake me at", "wake me up tomorrow", "remind me at"));
        actionSynonyms.put(Action.CANCEL_TIMER, Arrays.asList(
                "cancel the timer", "cancel timer", "cancel my timer", "cancel the timers", "cancel all timers",
                "stop the timer", "turn off the timer", "kill the timer",
                "cancel the alarm", "cancel alarm", "cancel my alarm", "cancel the alarms", "cancel all alarms",
                "stop the alarm", "turn off the alarm", "turn the alarm off", "kill the alarm"));
        // Voice-triggered routines. Parameter is the routine name; "nap" is the only one so far.
        // Vosk variants ("naptime", "a as m or nap") come from running TTS through the model.
        actionSynonyms.put(Action.ROUTINE, Arrays.asList(
                "nap", "naptime", "nap time", "power nap", "take a nap", "start a nap", "start my nap",
                "nap routine", "time for a nap", "asmr nap", "a s m r nap", "a as m r nap", "a s m or nap",
                "a as m or nap"));

        List<String> getTime = Arrays.asList("what's the time", "what time is it", "current time", "tell me the time");
        List<String> getWeather = Arrays.asList("what's the weather", "what's the temperature", "current weather",
                "current temperature", "tell me the weather", "tell me the temperature");

        List<String> utilities = new LinkedList<>();
        utilities.addAll(getTime);
        utilities.addAll(getWeather);

        actionSynonyms.put(Action.UTILITY, utilities);

        // Playful banter
        List<String> jorkeningBase = Arrays.asList(
                "jogging", "jorc running", "jaw cutting", "jerking",
                "ga running", "georgia running", "ga name",
                "drawer cutting", "drawer cunning", "door cutting", "jorc cutting",
                "darkening", "shortening", "jork getting",
                "george running", "george getting",
                "drug getting", "door getting", "door cunning",
                "door to me", "door opening", "jorc opening", "jork opening",
                "joking", "majorcan getting", "dorgan it", "jerk", "jerk opening", "jerk getting",
                "chortling", "door kidding", "drawer getting", "york and it");
        List<String> jorkening = generatePhrasePrefixes(jorkeningBase, "begin the ", "initiate the ", "begin ",
                "initiate ", "the in the ", "the and the ", "initiated ");
        List<String> theGame = Arrays.asList("lost the game");
        List<String> banter = new LinkedList<>();
        banter.addAll(theGame);
        banter.addAll(jorkening);
        actionSynonyms.put(Action.BANTER, banter);

        // Map all the ways a user might refer to a specific device
        targetSynonyms.put(Target.KITCHEN_LIGHTS, Arrays.asList("kitchen light", "kitchen lights", "cooking lights"));
        targetSynonyms.put(Target.LIVING_ROOM_TV, Arrays.asList("tv", "television", "living room tv", "the screen"));
        targetSynonyms.put(Target.BEDROOM_FAN, Arrays.asList("fan", "bedroom fan", "ceiling fan"));
        List<String> speakerSynonyms = new LinkedList<>(
                Arrays.asList("speaker", "music", "sound system", "stereo", "speaker array")); // Speaker selector +
                                                                                               // banter overlap
        speakerSynonyms.addAll(banter);
        speakerSynonyms.addAll(setTimer);
        speakerSynonyms.addAll(utilities);
        speakerSynonyms.addAll(jorkening);
        targetSynonyms.put(Target.SPEAKER_ARRAY, speakerSynonyms);

        // Map target capabilities to validate context
        targetCapabilities.put(Target.KITCHEN_LIGHTS, Arrays.asList(Action.TURN_ON, Action.TURN_OFF));
        targetCapabilities.put(Target.LIVING_ROOM_TV,
                Arrays.asList(Action.TURN_ON, Action.TURN_OFF, Action.INCREASE_VOLUME, Action.DECREASE_VOLUME));
        targetCapabilities.put(Target.BEDROOM_FAN, Arrays.asList(Action.TURN_ON, Action.TURN_OFF));
        // Added PLAY_MUSIC to the speaker array capabilities!
        targetCapabilities.put(Target.SPEAKER_ARRAY,
                Arrays.asList(Action.PLAY_MUSIC, Action.INCREASE_VOLUME, Action.DECREASE_VOLUME, Action.TURN_OFF));

        // --- Define the Parameters ---
        // The Key is what your backend will receive. The List is what the user might
        // say.
        parameterMap.put("jazz", Arrays.asList("jazz", "jazzy", "smooth jazz"));
        parameterMap.put("active", Arrays.asList("active", "energetic", "upbeat", "workout", "pump up"));
        parameterMap.put("epic", Arrays.asList("epic", "awesome", "power", "powerful", "heroic"));
        parameterMap.put("funk", Arrays.asList("funk", "funky", "groove"));
        parameterMap.put("lofi", Arrays.asList("lofi", "chill", "study"));
        parameterMap.put("relaxing", Arrays.asList("relaxing", "peaceful", "soothing", "calm"));
        parameterMap.put("classical", Arrays.asList("classical", "orchestral", "symphony", "piano music"));
        parameterMap.put("rock", Arrays.asList("rock", "rock and roll", "guitar music"));
        parameterMap.put("hip hop", Arrays.asList("hip hop", "hip-hop", "rap", "rap music"));
        parameterMap.put("country", Arrays.asList("country", "country music", "country western"));
        parameterMap.put("electronic", Arrays.asList("electronic", "edm", "electro", "dance music"));
        parameterMap.put("ambient", Arrays.asList("ambient", "atmospheric", "background music"));
        parameterMap.put("wakeup", Arrays.asList("wake up", "morning music", "sunrise music"));
        parameterMap.put("fancy_restaurant",
                Arrays.asList("fancy restaurant", "dinner music", "classy music", "elegant music"));
        parameterMap.put("meme", Arrays.asList("meme music", "funny music", "funny songs", "meme songs"));
        parameterMap.put("time", getTime);
        parameterMap.put("weather", getWeather);
        parameterMap.put("jorkening", jorkening);

        // Other less-important parameters:
        parameterMap.put("cooking curry", Arrays.asList("curry cooking music", "curry the music", "curry cooking tunes", "liquor cooking music"));
        parameterMap.put("microwave popcorn", Arrays.asList("popcorn", "popcorn music", "popcorn cooking music"));

        // Timer durations and alarm times are not listed here — TimeExpressionParser
        // extracts them from the free text in parse().
    }

    /**
     * Returns the key whose synonym is the longest phrase found in the text, so
     * "turn off the alarm" beats "turn off" and "start a timer" beats "start".
     * Ties go to the earlier key in insertion order.
     */
    private static <K> K longestMatch(Map<K, List<String>> dictionary, String paddedText, boolean wholeWord) {
        K best = null;
        int bestLength = 0;
        for (Map.Entry<K, List<String>> entry : dictionary.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (synonym.length() <= bestLength) continue;
                String needle = wholeWord ? " " + synonym + " " : " " + synonym;
                if (paddedText.contains(needle)) {
                    best = entry.getKey();
                    bestLength = synonym.length();
                }
            }
        }
        return best;
    }

    /**
     * The Lexer & Parser
     * Scans the raw text for known entity synonyms and resolves them into a
     * structured Command.
     */
    public ParsedCommand parse(String rawText) throws MissingContextualTargetException {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new ParsedCommand(Action.UNKNOWN, Target.UNKNOWN, null);
        }

        String normalizedText = " " + rawText.toLowerCase() + " "; // Pad for easier word boundary matching
        for (int i = 0; i < CORRECTION_PATTERNS.length; i++) {
            normalizedText = CORRECTION_PATTERNS[i].matcher(normalizedText).replaceAll(CORRECTION_REPLACEMENTS[i]);
        }

        // Lex the Action, Target (Device) and Parameter (e.g., Music Genre)
        Action foundAction = longestMatch(actionSynonyms, normalizedText, true);
        Target foundTarget = longestMatch(targetSynonyms, normalizedText, true);
        String foundParameter = longestMatch(parameterMap, normalizedText, false);
        if (foundAction == null) foundAction = Action.UNKNOWN;
        if (foundTarget == null) foundTarget = Target.UNKNOWN;

        // If they just said "play some music" with no genre, you can set a default
        if (foundParameter == null && foundAction == Action.PLAY_MUSIC) {
            foundParameter = "fancy_restaurant"; // Default genre if none specified
        }

        // If they said "play music", the context target inference might miss it. Force
        // the target to speakers.
        if (foundTarget == Target.UNKNOWN && normalizedText.contains(" music ")) {
            foundTarget = Target.SPEAKER_ARRAY;
        }

        // --- Timers & Alarms ---
        // Parameter formats: SET_TIMER -> total seconds, SET_ALARM -> ISO local date-time,
        // CANCEL_TIMER -> "timer", "alarm", or null for both.
        boolean alarmContext = foundAction == Action.SET_ALARM || normalizedText.contains(" alarm");
        if (foundAction == Action.ROUTINE) {
            // Routines own their timing; don't let "minutes" in the phrase turn this into a timer
            foundParameter = "nap";
        } else if (foundAction == Action.CANCEL_TIMER) {
            foundParameter = normalizedText.contains(" alarm") ? "alarm"
                    : normalizedText.contains(" timer") ? "timer" : null;
        } else if (alarmContext && TimeExpressionParser.parseClockTime(normalizedText, LocalDateTime.now(clock)) != null) {
            foundAction = Action.SET_ALARM;
            foundParameter = TimeExpressionParser.parseClockTime(normalizedText, LocalDateTime.now(clock)).toString();
        } else if (alarmContext || likelyTimerCommand(normalizedText) || foundAction == Action.SET_TIMER) {
            // Fuzzy timer detection: strong timer keywords override other actions, which
            // catches garbled transcriptions like "german for the time of five minute please".
            // An alarm request with a duration ("alarm in twenty minutes") is really a timer.
            Integer seconds = TimeExpressionParser.parseDurationSeconds(normalizedText);
            if (seconds != null || !alarmContext) {
                foundAction = Action.SET_TIMER;
                foundParameter = seconds != null ? String.valueOf(seconds) : null;
            } else {
                foundAction = Action.SET_ALARM;
                foundParameter = null;
            }
        }

        // --- Context Resolution ---
        // Some actions (timers, alarms, utilities) are global and don't require a device
        // target
        boolean isGlobalAction = foundAction.isGlobal();

        // If we found an action but no explicit target, try to infer it from history —
        // but only when the command refers back to something ("turn it up", "turn that
        // off") and the last device was used recently. A bare verb overheard in
        // conversation ("get a kill", "we played mario kart") must not act on whatever
        // device happened to be used last.
        boolean canUseHistory = REFERS_BACK.matcher(normalizedText).find()
                && lastTargetAt != null
                && Duration.between(lastTargetAt, LocalDateTime.now(clock)).compareTo(HISTORY_TTL) <= 0;
        if (foundTarget == Target.UNKNOWN && foundAction != Action.UNKNOWN && !isGlobalAction) {
            for (Target historicalTarget : canUseHistory ? targetHistory : Collections.<Target>emptyList()) {
                List<Action> capabilities = targetCapabilities.getOrDefault(historicalTarget, Collections.emptyList());
                if (capabilities.contains(foundAction)) {
                    foundTarget = historicalTarget;
                    break;
                }
            }

            // If STILL unknown after walking history, throw context error
            if (foundTarget == Target.UNKNOWN) {
                throw new MissingContextualTargetException(
                        "I heard an action, but I don't know what device to apply it to.");
            }
        }

        // --- Update Context History ---
        // If we resolved a target (explicitly or via context), push it to the top of
        // the history stack
        if (foundTarget != Target.UNKNOWN) {
            targetHistory.remove(foundTarget); // Remove to prevent duplicates
            targetHistory.addFirst(foundTarget); // Set as most recent
            if (targetHistory.size() > MAX_HISTORY) {
                targetHistory.removeLast(); // Keep the backlog from ballooning
            }
            lastTargetAt = LocalDateTime.now(clock);
        }

        return new ParsedCommand(foundAction, foundTarget, foundParameter);
    }
}
