package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.Before;
import org.junit.Test;

import com.jarvis.ParsedCommand.Action;
import com.jarvis.ParsedCommand.Target;

public class IntentParserTest {

    private IntentParser parser;

    @Before
    public void setUp() {
        ZoneId zone = ZoneId.systemDefault();
        Clock fixed = Clock.fixed(LocalDateTime.of(2026, 10, 17, 14, 0).atZone(zone).toInstant(), zone);
        parser = new IntentParser(fixed);
    }

    @Test
    public void timerWithDuration() throws Exception {
        ParsedCommand c = parser.parse("set a timer for five minutes");
        assertEquals(Action.SET_TIMER, c.action);
        assertEquals("300", c.parameter);
        assertTrue(c.isValid());
    }

    @Test
    public void longestPhraseWins() throws Exception {
        // "start a timer" must beat "start" (TURN_ON); "turn off the alarm" must beat "turn off"
        assertEquals(Action.SET_TIMER, parser.parse("start a timer for ten minutes").action);
        ParsedCommand c = parser.parse("turn off the alarm");
        assertEquals(Action.CANCEL_TIMER, c.action);
        assertEquals("alarm", c.parameter);
    }

    @Test
    public void alarms() throws Exception {
        ParsedCommand c = parser.parse("set an alarm for seven thirty");
        assertEquals(Action.SET_ALARM, c.action);
        assertEquals("2026-10-17T19:30", c.parameter);
        assertTrue(c.isValid());

        ParsedCommand relative = parser.parse("set an alarm in twenty minutes");
        assertEquals(Action.SET_TIMER, relative.action);
        assertEquals("1200", relative.parameter);
    }

    @Test
    public void cancelTimer() throws Exception {
        ParsedCommand c = parser.parse("cancel the timer");
        assertEquals(Action.CANCEL_TIMER, c.action);
        assertEquals("timer", c.parameter);
    }

    @Test
    public void musicCommandsUnchanged() throws Exception {
        ParsedCommand kill = parser.parse("kill the music");
        assertEquals(Action.TURN_OFF, kill.action);
        assertEquals(Target.SPEAKER_ARRAY, kill.target);

        ParsedCommand jazz = parser.parse("play some jazz music");
        assertEquals(Action.PLAY_MUSIC, jazz.action);
        assertEquals("jazz", jazz.parameter);

        ParsedCommand plain = parser.parse("play some music");
        assertEquals("fancy_restaurant", plain.parameter);
    }

    @Test
    public void contextCarriesBetweenCommands() throws Exception {
        parser.parse("play some music");
        ParsedCommand c = parser.parse("turn it up");
        assertEquals(Action.INCREASE_VOLUME, c.action);
        assertEquals(Target.SPEAKER_ARRAY, c.target);
    }

    @Test
    public void utilityIsValidWithoutDevice() throws Exception {
        ParsedCommand c = parser.parse("what time is it");
        assertEquals(Action.UTILITY, c.action);
        assertTrue(c.isValid());
    }
}
