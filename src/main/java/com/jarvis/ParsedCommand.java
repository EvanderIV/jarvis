package com.jarvis;

// A structured record to hold the final parsed command
public class ParsedCommand {

    // --- The "Tokens" (Enums) ---
    public enum Action {
        TURN_ON, TURN_OFF, INCREASE_VOLUME, DECREASE_VOLUME, PLAY_MUSIC, BANTER, UTILITY, SET_TIMER,
        SET_ALARM, CANCEL_TIMER, ROUTINE, UNKNOWN;

        /** Actions that don't apply to a device and so need no target. */
        public boolean isGlobal() {
            return this == SET_TIMER || this == SET_ALARM || this == CANCEL_TIMER || this == UTILITY
                    || this == ROUTINE;
        }
    }

    public enum Target {
        KITCHEN_LIGHTS, LIVING_ROOM_TV, BEDROOM_FAN, SPEAKER_ARRAY, UNKNOWN
    }

    public final Action action;
    public final Target target;
    public final String parameter;

    public ParsedCommand(Action action, Target target, String parameter) {
        this.action = action;
        this.target = target;
        this.parameter = parameter;
    }

    public boolean isValid() {
        return action != Action.UNKNOWN && (target != Target.UNKNOWN || action.isGlobal());
    }
    
    @Override
    public String toString() {
        return "Command(Action=" + action + ", Target=" + target + ", Parameter=" + parameter + ")";
    }
}
