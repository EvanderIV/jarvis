package com.jarvis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     *       -> ["begin the jogging", "initiate the jogging"]
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
     * Converts an integer to its English word representation (e.g., 1 -> "one", 21 -> "twenty one")
     */
    private String numberToEnglish(int num) {
        String[] ones = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        
        if (num == 0) return "zero";
        if (num < 10) return ones[num];
        if (num < 20) {
            String[] teens = {"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", 
                             "sixteen", "seventeen", "eighteen", "nineteen"};
            return teens[num - 10];
        }
        int ten = num / 10;
        int one = num % 10;
        return tens[ten] + (one > 0 ? " " + ones[one] : "");
    }

    public IntentParser() {
        // Map all the ways a user might phrase an action
        actionSynonyms.put(Action.TURN_ON, Arrays.asList("turn on", "enable", "start", "lights on", "activate", "turn it on", "turn that on", "turn that thing on"));
        actionSynonyms.put(Action.TURN_OFF, Arrays.asList("turn off", "disable", "stop", "lights off", "kill", "turn it off", "turn that off", "turn that thing off"));
        actionSynonyms.put(Action.INCREASE_VOLUME, Arrays.asList("louder", "turn up", "turn it up", "crank it up", "volume up"));
        actionSynonyms.put(Action.DECREASE_VOLUME, Arrays.asList("quieter", "turn down", "turn it down", "crank it down", "volume down"));
        actionSynonyms.put(Action.PLAY_MUSIC, Arrays.asList("play", "put on", "crank", "listen to"));
        List<String> setTimer = Arrays.asList("set a timer", "start a timer", "countdown", "remind me in", "remind me to");
        actionSynonyms.put(Action.SET_TIMER, setTimer);

        List<String> getTime = Arrays.asList("what's the time", "what time is it", "current time", "tell me the time");
        List<String> getWeather = Arrays.asList("what's the weather", "what's the temperature", "current weather", "current temperature", "tell me the weather", "tell me the temperature");
        
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
            "door to me"
        );
        List<String> jorkening = generatePhrasePrefixes(jorkeningBase, "begin the ", "initiate the ");
        List<String> theGame = Arrays.asList("lost the game");
        List<String> banter = new LinkedList<>();
        banter.addAll(jorkening);
        banter.addAll(theGame);
        actionSynonyms.put(Action.BANTER, banter);

        // Map all the ways a user might refer to a specific device
        targetSynonyms.put(Target.KITCHEN_LIGHTS, Arrays.asList("kitchen light", "kitchen lights", "cooking lights"));
        targetSynonyms.put(Target.LIVING_ROOM_TV, Arrays.asList("tv", "television", "living room tv", "the screen"));
        targetSynonyms.put(Target.BEDROOM_FAN, Arrays.asList("fan", "bedroom fan", "ceiling fan"));
        List<String> speakerSynonyms = new LinkedList<>(Arrays.asList("speaker", "music", "sound system", "stereo", "speaker array")); // Speaker selector + banter overlap
        speakerSynonyms.addAll(banter);
        speakerSynonyms.addAll(setTimer);
        speakerSynonyms.addAll(utilities);
        targetSynonyms.put(Target.SPEAKER_ARRAY, speakerSynonyms);

        // Map target capabilities to validate context
        targetCapabilities.put(Target.KITCHEN_LIGHTS, Arrays.asList(Action.TURN_ON, Action.TURN_OFF));
        targetCapabilities.put(Target.LIVING_ROOM_TV, Arrays.asList(Action.TURN_ON, Action.TURN_OFF, Action.INCREASE_VOLUME, Action.DECREASE_VOLUME));
        targetCapabilities.put(Target.BEDROOM_FAN, Arrays.asList(Action.TURN_ON, Action.TURN_OFF));
        // Added PLAY_MUSIC to the speaker array capabilities!
        targetCapabilities.put(Target.SPEAKER_ARRAY, Arrays.asList(Action.PLAY_MUSIC, Action.INCREASE_VOLUME, Action.DECREASE_VOLUME, Action.TURN_OFF));

        // --- Define the Parameters ---
        // The Key is what your backend will receive. The List is what the user might say.
        parameterMap.put("jazz", Arrays.asList("jazz", "jazzy", "smooth jazz"));
        parameterMap.put("active", Arrays.asList("active", "energetic", "upbeat", "workout", "pump up"));
        parameterMap.put("funk", Arrays.asList("funk", "funky", "groove"));
        parameterMap.put("lofi", Arrays.asList("lofi", "chill", "study", "relaxing", "peaceful"));
        parameterMap.put("classical", Arrays.asList("classical", "orchestral", "symphony", "piano music"));
        parameterMap.put("rock", Arrays.asList("rock", "rock and roll", "guitar music"));
        parameterMap.put("hip hop", Arrays.asList("hip hop", "hip-hop", "rap", "rap music"));
        parameterMap.put("country", Arrays.asList("country", "country music", "country western"));
        parameterMap.put("electronic", Arrays.asList("electronic", "edm", "electro", "dance music"));
        parameterMap.put("ambient", Arrays.asList("ambient", "atmospheric", "background music"));
        parameterMap.put("wakeup", Arrays.asList("wake up", "morning music", "sunrise music"));
        parameterMap.put("fancy_restaurant", Arrays.asList("fancy restaurant", "dinner music", "classy music", "elegant music"));
        parameterMap.put("time", getTime);
        parameterMap.put("weather", getWeather);
        parameterMap.put("jorkening", jorkening);
        // Set param to the number of minutes for timers if they say "set a timer for 5 minutes" or "remind me in 10 minutes"
        for (int i = 1; i <= 60; i++) {
            String numberWord = numberToEnglish(i);
            parameterMap.put(Integer.toString(i), Arrays.asList(
                "remind me in " + numberWord + " minutes",
                "set a timer for " + numberWord + " minutes",
                "set a timer " + numberWord + " minutes",
                "countdown " + numberWord + " minutes"
            ));
        }
    }

    /**
     * The Lexer & Parser
     * Scans the raw text for known entity synonyms and resolves them into a structured Command.
     */
    public ParsedCommand parse(String rawText) throws MissingContextualTargetException {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new ParsedCommand(Action.UNKNOWN, Target.UNKNOWN, null);
        }

        String normalizedText = " " + rawText.toLowerCase() + " "; // Pad for easier word boundary matching
        normalizedText = normalizedText.replaceAll("turned", "turn").replaceAll("played", "play"); // Handle past tense variations like "turned on" -> "turn on"
        
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
            if (foundAction != Action.UNKNOWN) break;
        }

        // Lex the Target (Device)
        for (Map.Entry<Target, List<String>> entry : targetSynonyms.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (normalizedText.contains(" " + synonym + " ")) {
                    foundTarget = entry.getKey();
                    break;
                }
            }
            if (foundTarget != Target.UNKNOWN) break;
        }

        // Lex the Parameter (e.g., Music Genre)
        for (Map.Entry<String, List<String>> entry : parameterMap.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (normalizedText.contains(" " + synonym + " ") || normalizedText.contains(" " + synonym)) {
                    foundParameter = entry.getKey(); // Set the parameter to the canonical genre key
                    break;
                }
            }
            if (foundParameter != null) break;
        }
        
        // If they just said "play some music" with no genre, you can set a default
        if (foundParameter == null && foundAction == Action.PLAY_MUSIC) {
            foundParameter = "fancy_restaurant"; // Default genre if none specified
        }
        
        // If they said "play music", the context target inference might miss it. Force the target to speakers.
        if (foundTarget == Target.UNKNOWN && normalizedText.contains(" music ")) {
            foundTarget = Target.SPEAKER_ARRAY;
        }

        // --- Context Resolution ---
        // If we found an action but no explicit target, try to infer it from history
        if (foundTarget == Target.UNKNOWN && foundAction != Action.UNKNOWN) {
            for (Target historicalTarget : targetHistory) {
                List<Action> capabilities = targetCapabilities.getOrDefault(historicalTarget, Collections.emptyList());
                if (capabilities.contains(foundAction)) {
                    foundTarget = historicalTarget;
                    break;
                }
            }
            
            // If STILL unknown after walking history, throw context error
            if (foundTarget == Target.UNKNOWN) {
                throw new MissingContextualTargetException("I heard an action, but I don't know what device to apply it to.");
            }
        }

        // --- Update Context History ---
        // If we resolved a target (explicitly or via context), push it to the top of the history stack
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