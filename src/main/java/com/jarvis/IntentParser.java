package com.jarvis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedList;
import java.util.Collections;

import com.jarvis.ParsedCommand.*;

public class IntentParser {

    // --- The Lexer Dictionaries (Synonym Mapping) ---
    private final Map<Action, List<String>> actionSynonyms = new HashMap<>();
    private final Map<Target, List<String>> targetSynonyms = new HashMap<>();

    // NEW: A dictionary to map spoken descriptions to canonical genres
    private final Map<String, List<String>> parameterMap = new HashMap<>();

    // --- Context & Capabilities ---
    private final LinkedList<Target> targetHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 5;
    private final Map<Target, List<Action>> targetCapabilities = new HashMap<>();

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
     * Converts an integer to its English word representation (e.g., 1 -> "one", 21
     * -> "twenty one")
     */
    private String numberToEnglish(int num) {
        String[] ones = { "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine" };
        String[] tens = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

        if (num == 0)
            return "zero";
        if (num < 10)
            return ones[num];
        if (num < 20) {
            String[] teens = { "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                    "sixteen", "seventeen", "eighteen", "nineteen" };
            return teens[num - 10];
        }
        int ten = num / 10;
        int one = num % 10;
        return tens[ten] + (one > 0 ? " " + ones[one] : "");
    }

    /**
     * Converts English number words to integers (e.g., "five" -> 5, "twenty three"
     * -> 23)
     * Used for fuzzy timer parsing when Vosk mishears the command
     */
    private Integer englishToNumber(String words) {
        String[] ones = { "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine" };
        String[] teens = { "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                "sixteen", "seventeen", "eighteen", "nineteen" };
        String[] tens = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

        words = words.toLowerCase().trim();

        // Try direct match for single words
        for (int i = 0; i < ones.length; i++) {
            if (words.equals(ones[i]))
                return i;
        }
        for (int i = 0; i < teens.length; i++) {
            if (words.equals(teens[i]))
                return i + 10;
        }
        for (int i = 0; i < tens.length; i++) {
            if (words.equals(tens[i]))
                return i * 10;
        }

        // Try compound numbers like "twenty three"
        String[] parts = words.split("\\s+");
        if (parts.length == 2) {
            Integer tenVal = null;
            Integer oneVal = null;

            for (int i = 0; i < tens.length; i++) {
                if (parts[0].equals(tens[i])) {
                    tenVal = i * 10;
                    break;
                }
            }
            for (int i = 0; i < ones.length; i++) {
                if (parts[1].equals(ones[i])) {
                    oneVal = i;
                    break;
                }
            }
            if (tenVal != null && oneVal != null) {
                return tenVal + oneVal;
            }
        }

        return null;
    }

    /**
     * Attempts to extract a timer command from garbled/bad parses.
     * Looks for number words followed by "minute" keywords even if the full phrase
     * isn't recognized.
     * E.g., "german for the time of five minute please" -> extracts "5"
     */
    private String extractTimerParameter(String normalizedText) {
        String[] timeKeywords = { "minute", "minutes", "sec", "secs", "second", "seconds", "hour", "hours" };
        // String[] numberWords = { "zero", "one", "two", "three", "four", "five",
        // "six", "seven", "eight", "nine",
        // "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        // "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
        // "sixty" };

        // Split text into words
        String[] words = normalizedText.toLowerCase().split("\\s+");

        // Look for patterns: [number word/digit] + [time keyword]
        for (int i = 0; i < words.length - 1; i++) {
            String currentWord = words[i].replaceAll("[^a-z0-9]", ""); // Remove punctuation
            String nextWord = words[i + 1].replaceAll("[^a-z0-9]", "");

            // Check if current word is a number or contains a digit
            Integer num = null;
            if (currentWord.matches("\\d+")) {
                num = Integer.parseInt(currentWord);
            } else {
                num = englishToNumber(currentWord);
            }

            // Check if next word is a time keyword
            if (num != null && num > 0 && num <= 60) {
                for (String keyword : timeKeywords) {
                    if (nextWord.startsWith(keyword)) {
                        return String.valueOf(num);
                    }
                }
            }
        }

        // Fallback: Also check if any number appears before a time keyword anywhere in
        // the text
        // This handles cases like "they were for five minutes" where there might be
        // words between
        String text = normalizedText.replaceAll("[^a-z0-9\\s]", "").toLowerCase();
        Pattern numPattern = Pattern.compile(
                "\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty)\\b");
        Matcher numMatcher = numPattern.matcher(text);

        Pattern timePattern = Pattern.compile("\\b(minute|minutes|sec|secs|second|seconds|hour|hours)\\b");
        Matcher timeMatcher = timePattern.matcher(text);

        if (numMatcher.find() && timeMatcher.find()) {
            // If a number appears before a time keyword, extract the first number found
            String numStr = numMatcher.group(1);
            Integer num = null;
            if (numStr.matches("\\d+")) {
                num = Integer.parseInt(numStr);
            } else {
                num = englishToNumber(numStr);
            }
            if (num != null && num > 0 && num <= 60) {
                return String.valueOf(num);
            }
        }

        return null;
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

    public IntentParser() {
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
                "drawer cutting", "door cutting", "jorc cutting",
                "darkening", "shortening", "jork getting",
                "george running", "george getting",
                "drug getting", "door getting", "door cunning",
                "door to me", "door opening", "jorc opening", "jork opening",
                "joking", "majorcan getting", "dorgan it", "jerk", "jerk opening", "jerk getting",
                "chortling", "door kidding");
        List<String> jorkening = generatePhrasePrefixes(jorkeningBase, "begin the ", "initiate the ", "begin ",
                "initiate ", "the in the ", "the and the ");
        List<String> theGame = Arrays.asList("lost the game");
        List<String> banter = new LinkedList<>();
        banter.addAll(jorkening);
        banter.addAll(theGame);
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
        parameterMap.put("time", getTime);
        parameterMap.put("weather", getWeather);
        parameterMap.put("jorkening", jorkening);
        // Set param to the number of minutes for timers if they say "set a timer for 5
        // minutes" or "remind me in 10 minutes"
        for (int i = 1; i <= 60; i++) {
            String numberWord = numberToEnglish(i);
            parameterMap.put(Integer.toString(i), Arrays.asList(
                    "remind me in " + numberWord + " minutes",
                    "set a timer for " + numberWord + " minutes",
                    "set a timer " + numberWord + " minutes",
                    "countdown " + numberWord + " minutes"));
        }
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
        normalizedText = normalizedText.replaceAll("turned", "turn").replaceAll("played", "play")
                .replaceAll("playful music", "play some music").replaceAll("place music", "play some music")
                .replaceAll("place and music", "play some music").replaceAll("place the music", "play some music")
                .replaceAll("pleasure music", "play some music")
                .replaceAll("place the music", "play some music").replaceAll("please music", "play some music")
                .replaceAll("pleasant music", "play some music")
                .replaceAll("please show music", "play some music")
                .replaceAll("play acted music", "play active music")
                .replaceAll("play acted music", "they active music")
                .replaceAll("question music", "they active music")
                .replaceAll("kill music", "kill the music")
                .replaceAll("still the music", "kill the music")
                .replaceAll("killed the music", "kill the music")
                .replaceAll("killer music", "kill the music")
                .replaceAll("feel the music", "kill the music");

        Action foundAction = Action.UNKNOWN;
        Target foundTarget = Target.UNKNOWN;
        String foundParameter = null;

        // Lex the Action
        for (Map.Entry<Action, List<String>> entry : actionSynonyms.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (normalizedText.contains(" " + synonym + " ")) {
                    foundAction = entry.getKey();
                    break;
                }
            }
            if (foundAction != Action.UNKNOWN)
                break;
        }

        // Lex the Target (Device)
        for (Map.Entry<Target, List<String>> entry : targetSynonyms.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (normalizedText.contains(" " + synonym + " ")) {
                    foundTarget = entry.getKey();
                    break;
                }
            }
            if (foundTarget != Target.UNKNOWN)
                break;
        }

        // Lex the Parameter (e.g., Music Genre)
        for (Map.Entry<String, List<String>> entry : parameterMap.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (normalizedText.contains(" " + synonym + " ") || normalizedText.contains(" " + synonym)) {
                    foundParameter = entry.getKey(); // Set the parameter to the canonical genre key
                    break;
                }
            }
            if (foundParameter != null)
                break;
        }

        // If they just said "play some music" with no genre, you can set a default
        if (foundParameter == null && foundAction == Action.PLAY_MUSIC) {
            foundParameter = "fancy_restaurant"; // Default genre if none specified
        }

        // If they said "play music", the context target inference might miss it. Force
        // the target to speakers.
        if (foundTarget == Target.UNKNOWN && normalizedText.contains(" music ")) {
            foundTarget = Target.SPEAKER_ARRAY;
        }

        // --- Fuzzy Timer Detection ---
        // If text contains strong timer keywords, treat as timer command (overrides
        // other actions)
        if (likelyTimerCommand(normalizedText)) {
            foundAction = Action.SET_TIMER;
            // Try to extract the time parameter if we haven't already
            if (foundParameter == null) {
                foundParameter = extractTimerParameter(normalizedText);
            }
        }
        // If we found SET_TIMER but no parameter, try fuzzy extraction
        else if (foundAction == Action.SET_TIMER && foundParameter == null) {
            foundParameter = extractTimerParameter(normalizedText);
        }

        // --- Context Resolution ---
        // Some actions (SET_TIMER, UTILITY) are global and don't require a device
        // target
        boolean isGlobalAction = foundAction == Action.SET_TIMER || foundAction == Action.UTILITY;

        // If we found an action but no explicit target, try to infer it from history
        if (foundTarget == Target.UNKNOWN && foundAction != Action.UNKNOWN && !isGlobalAction) {
            for (Target historicalTarget : targetHistory) {
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
        }

        return new ParsedCommand(foundAction, foundTarget, foundParameter);
    }
}