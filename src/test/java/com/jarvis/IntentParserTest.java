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

    @Test(expected = MissingContextualTargetException.class)
    public void bareVerbDoesNotBorrowLastDevice() throws Exception {
        parser.parse("play some music");
        parser.parse("i got a kill");
    }

    @Test
    public void contextExpires() throws Exception {
        MutableClock clock = new MutableClock(LocalDateTime.of(2026, 10, 17, 14, 0));
        IntentParser timed = new IntentParser(clock);
        timed.parse("play some music");
        clock.now = clock.now.plusMinutes(4);
        assertEquals(Target.SPEAKER_ARRAY, timed.parse("turn it up").target);
        clock.now = clock.now.plusMinutes(6);
        try {
            timed.parse("turn it up");
            org.junit.Assert.fail("context should have expired");
        } catch (MissingContextualTargetException expected) {
        }
    }

    private static class MutableClock extends Clock {
        LocalDateTime now;
        MutableClock(LocalDateTime now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now.atZone(getZone()).toInstant(); }
    }

    @Test
    public void napTriggers() throws Exception {
        // Phrasings as Vosk actually transcribed them
        for (String text : new String[] { "start a nap", "naptime", "nap time", "power nap", "take a nap",
                "start my nap", "time for a nap", "a as m or nap" }) {
            ParsedCommand c = parser.parse(text);
            assertEquals(text, Action.ROUTINE, c.action);
            assertEquals(text, "nap", c.parameter);
            assertTrue(text, c.isValid());
        }
    }

    @Test
    public void utilityIsValidWithoutDevice() throws Exception {
        ParsedCommand c = parser.parse("what time is it");
        assertEquals(Action.UTILITY, c.action);
        assertTrue(c.isValid());
    }
}
