package com.jarvis;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.jarvis.ParsedCommand.*;

/**
 * Handles the execution of parsed commands.
 * Validates commands and executes the appropriate action on the specified target.
 */
public class CommandFulfiller {

    private final Map<Target, Map<Action, Boolean>> deviceCapabilities = new HashMap<>();
    
    // Injected Controllers
    private final MusicManager musicManager;
    private final LmsController lmsController;
    private final RoutineEngine routineEngine;
    private final TimerManager timerManager;

    private static final int MAX_TIMER_SECONDS = 24 * 60 * 60;

    public CommandFulfiller(MusicManager musicManager, LmsController lmsController, RoutineEngine routineEngine,
            TimerManager timerManager) {
        this.musicManager = musicManager;
        this.lmsController = lmsController;
        this.routineEngine = routineEngine;
        this.timerManager = timerManager;
        initializeCapabilities();
    }

    /**
     * Initialize device capabilities to validate commands
     */
    private void initializeCapabilities() {
        // Kitchen Lights
        Map<Action, Boolean> kitchenLights = new HashMap<>();
        kitchenLights.put(Action.TURN_ON, true);
        kitchenLights.put(Action.TURN_OFF, true);
        deviceCapabilities.put(Target.KITCHEN_LIGHTS, kitchenLights);

        // Living Room TV
        Map<Action, Boolean> tv = new HashMap<>();
        tv.put(Action.TURN_ON, true);
        tv.put(Action.TURN_OFF, true);
        tv.put(Action.INCREASE_VOLUME, true);
        tv.put(Action.DECREASE_VOLUME, true);
        deviceCapabilities.put(Target.LIVING_ROOM_TV, tv);

        // Bedroom Fan
        Map<Action, Boolean> fan = new HashMap<>();
        fan.put(Action.TURN_ON, true);
        fan.put(Action.TURN_OFF, true);
        deviceCapabilities.put(Target.BEDROOM_FAN, fan);

        // Speaker Array
        Map<Action, Boolean> speakers = new HashMap<>();
        speakers.put(Action.PLAY_MUSIC, true);
        speakers.put(Action.INCREASE_VOLUME, true);
        speakers.put(Action.DECREASE_VOLUME, true);
        speakers.put(Action.TURN_OFF, true);
        speakers.put(Action.BANTER, true); // Added Banter explicitly here
        deviceCapabilities.put(Target.SPEAKER_ARRAY, speakers);
    }

    /**
     * Execute a parsed command
     */
    public CommandResult fulfill(ParsedCommand command) throws CommandUnfulfillableException {
        if (!command.isValid()) {
            return new CommandResult(false, "Invalid command: action or target is UNKNOWN");
        }

        // Validate that the target supports this action (global actions have no device)
        if (!command.action.isGlobal() && !canExecute(command.target, command.action)) {
            return new CommandResult(false, 
                String.format("%s does not support action %s", command.target, command.action));
        }

        // Execute the appropriate handler
        return executeCommand(command);
    }

    /**
     * Check if a target device supports a given action
     */
    private boolean canExecute(Target target, Action action) {
        Map<Action, Boolean> capabilities = deviceCapabilities.get(target);
        return capabilities != null && capabilities.getOrDefault(action, false);
    }

    /**
     * Route to the appropriate handler based on action type
     */
    private CommandResult executeCommand(ParsedCommand command) {
        switch (command.action) {
            case TURN_ON:
                return handleTurnOn(command);
            case TURN_OFF:
                return handleTurnOff(command);
            case INCREASE_VOLUME:
                return handleIncreaseVolume(command);
            case DECREASE_VOLUME:
                return handleDecreaseVolume(command);
            case PLAY_MUSIC:
                return handlePlayMusic(command);
            case SET_TIMER:
                return handleSetTimer(command);
            case SET_ALARM:
                return handleSetAlarm(command);
            case CANCEL_TIMER:
                return handleCancelTimer(command);
            case BANTER:
                return handleBanter(command);
            case UTILITY:
                return handleUtility(command);
            default:
                return new CommandResult(false, "Unknown action: " + command.action);
        }
    }

    private CommandResult handleTurnOn(ParsedCommand command) {
        String message = String.format("Turning on %s", formatTargetName(command.target));
        System.out.println("[+] " + message);
        // TODO: Send MQTT/HTTP command to device
        return new CommandResult(true, message);
    }

    private CommandResult handleTurnOff(ParsedCommand command) {
        String message = String.format("Turning off %s", formatTargetName(command.target));
        System.out.println("[+] " + message);
        
        // Intercept speaker shutdowns to actually stop the music and mute the array
        if (command.target == Target.SPEAKER_ARRAY) {
            // Cancel in-progress routines first so a pending theme switch can't restart the music
            routineEngine.cancelActiveRoutines();
            musicManager.stopMusic();
            List<String> targetMacs = new ArrayList<>(); // Empty list applies to all speakers
            lmsController.mute(targetMacs);
            message = "Music stopped and speakers muted.";
        } else {
            // TODO: Send MQTT/HTTP command to smart plugs/lights
        }
        
        return new CommandResult(true, message);
    }

    private CommandResult handleIncreaseVolume(ParsedCommand command) {
        String message = String.format("Increasing volume on %s", formatTargetName(command.target));
        System.out.println("[+] " + message);
        
        if (command.target == Target.SPEAKER_ARRAY) {
            // Note: To cleanly increment volume, you'd track state locally or query LMS.
            // For now, we will just set it to a static "loud" volume.
            lmsController.setVolume(new ArrayList<>(), 75);
        }
        
        return new CommandResult(true, message);
    }

    private CommandResult handleDecreaseVolume(ParsedCommand command) {
        String message = String.format("Decreasing volume on %s", formatTargetName(command.target));
        System.out.println("[+] " + message);
        
        if (command.target == Target.SPEAKER_ARRAY) {
            // Note: Same as above. Hardcoded to a "quiet" state for now.
            lmsController.setVolume(new ArrayList<>(), 25);
        }
        
        return new CommandResult(true, message);
    }

    private static final int DEFAULT_VOLUME_FALLBACK = 60;

    private CommandResult handlePlayMusic(ParsedCommand command) {
        String genre = command.parameter != null ? command.parameter : "default";

        // A manual music request takes over from any routine in progress (its fades and
        // theme switches would otherwise keep overriding what was just asked for).
        routineEngine.cancelActiveRoutines();

        if ("asmr".equalsIgnoreCase(genre)) {
            List<String> bedroomMacs = lmsController.getSpeakersMatchingAlias("bedroom");
            if (bedroomMacs.isEmpty()) {
                return new CommandResult(false, "No bedroom speaker registered — cannot start ASMR sleep routine.");
            }
            routineEngine.createRoutine(bedroomMacs)
                    .setVolumeRatio(0.5)
                    .playTheme(new String[] { "+ASMR" })
                    .fadeVolumeRatio(0.5, 0.0, 3 * 60 * 60)
                    .pausePlayback()
                    .triggerNow();
            return new CommandResult(true, "Starting ASMR sleep routine on bedroom speaker — fading to silence over 3 hours.");
        }

        String message = String.format("Playing %s music on %s", genre, formatTargetName(command.target));
        System.out.println("[+] " + message);

        List<String> targetMacs = new ArrayList<>();
        musicManager.playMusic(genre, targetMacs);

        double volumeRatio = "epic".equals(genre) ? 0.9 : 0.7;
        for (String mac : lmsController.getAllRegisteredSpeakers()) {
            int speakerMax = lmsController.getDefaultVolume(mac, DEFAULT_VOLUME_FALLBACK);
            lmsController.setVolume(List.of(mac), (int) Math.round(volumeRatio * speakerMax));
        }

        return new CommandResult(true, message);
    }

    private CommandResult handleSetTimer(ParsedCommand command) {
        if (command.parameter == null) {
            return new CommandResult(false, "Heard a timer request but couldn't make out how long.");
        }
        int seconds;
        try {
            seconds = Integer.parseInt(command.parameter);
        } catch (NumberFormatException e) {
            return new CommandResult(false, "Unrecognized timer duration: " + command.parameter);
        }
        if (seconds <= 0 || seconds > MAX_TIMER_SECONDS) {
            return new CommandResult(false, "Timer duration out of range: " + seconds + "s");
        }
        String message = timerManager.startTimer(seconds);
        System.out.println("[+] " + message);
        return new CommandResult(true, message);
    }

    private CommandResult handleSetAlarm(ParsedCommand command) {
        if (command.parameter == null) {
            return new CommandResult(false, "Heard an alarm request but couldn't make out the time.");
        }
        LocalDateTime when;
        try {
            when = LocalDateTime.parse(command.parameter);
        } catch (Exception e) {
            return new CommandResult(false, "Unrecognized alarm time: " + command.parameter);
        }
        String message = timerManager.setAlarm(when);
        if (message == null) {
            return new CommandResult(false, "That alarm time has already passed.");
        }
        System.out.println("[+] " + message);
        return new CommandResult(true, message);
    }

    private CommandResult handleCancelTimer(ParsedCommand command) {
        TimerManager.Kind kind = "alarm".equals(command.parameter) ? TimerManager.Kind.ALARM
                : "timer".equals(command.parameter) ? TimerManager.Kind.TIMER : null;
        int cancelled = timerManager.cancel(kind);
        String what = kind == null ? "timer/alarm" : kind.toString().toLowerCase();
        String message = cancelled == 0 ? "No " + what + " was set."
                : "Cancelled " + cancelled + " " + what + (cancelled == 1 ? "" : "s") + ".";
        System.out.println("[+] " + message);
        return new CommandResult(true, message);
    }

    private CommandResult handleBanter(ParsedCommand command) {
        String response = generateBanterResponse(command.parameter);
        System.out.println("[+] " + response);
        
        // If the banter parameter is an audio meme you have in the music.json, play it!
        if ("jorkening".equals(command.parameter) || "theGame".equals(command.parameter)) {
            System.out.println("[*] Triggering audio meme for: " + command.parameter);
            musicManager.playMusic(command.parameter, new ArrayList<>());
        }
        
        return new CommandResult(true, response);
    }

    private CommandResult handleUtility(ParsedCommand command) {
        String message = null;
        if (command.parameter != null) {
            message = "Current time: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a"));
        } else {
            message = "Utility command received";
        }
        System.out.println("[+] " + message);
        return new CommandResult(true, message);
    }

    private String formatTargetName(Target target) {
        return target.toString()
            .replace("_", " ")
            .toLowerCase();
    }

    private String generateBanterResponse(String parameter) {
        String[] reponsesGeneric = {
            "I'm not sure I follow.",
            "I'm not programmed to understand what you just said.",
        };
        String[] responsesHumorRetort = {
            "Very funny.",
            "If my programmer had thought to give me a sense of humor, perhaps I could laugh at that.",
        };
        String[] responsesJorkening = {
            "This again?"
        };
        switch (parameter != null ? parameter : "") {
            case "humorRetort":
                return randFromArray(responsesHumorRetort);
            case "jorkening":
                return randFromArray(responsesJorkening);
            default:
                return randFromArray(reponsesGeneric);
        }
    }

    String randFromArray(String[] arr) {
        return arr[(int) (Math.random() * arr.length)];
    }

    /**
     * Result object for command execution
     */
    public static class CommandResult {
        public final boolean success;
        public final String message;

        public CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        @Override
        public String toString() {
            return (success ? "[+]" : "[-]") + " " + message;
        }
    }
}