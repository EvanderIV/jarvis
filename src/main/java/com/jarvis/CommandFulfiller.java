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
    
    public CommandFulfiller(MusicManager musicManager, LmsController lmsController) {
        this.musicManager = musicManager;
        this.lmsController = lmsController;
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

        // Validate that the target supports this action
        if (!canExecute(command.target, command.action)) {
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

    private CommandResult handlePlayMusic(ParsedCommand command) {
        String genre = command.parameter != null ? command.parameter : "default";
        String message = String.format("Playing %s music on %s", genre, formatTargetName(command.target));
        System.out.println("[+] " + message);
        
        // Pass an empty list to target ALL registered speakers.
        // If you map specific Target enums to MACs later, you'd populate this list!
        List<String> targetMacs = new ArrayList<>();
        musicManager.playMusic(genre, targetMacs);
        
        return new CommandResult(true, message);
    }

    private CommandResult handleSetTimer(ParsedCommand command) {
        String duration = command.parameter != null ? command.parameter : "unknown";
        String message = String.format("Setting timer for %s minutes", duration);
        System.out.println("[+] " + message);
        // TODO: Start a timer thread or send to device
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
        switch (parameter) {
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