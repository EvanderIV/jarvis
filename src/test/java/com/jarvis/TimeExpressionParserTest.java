package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.LocalDateTime;

import org.junit.Test;

public class TimeExpressionParserTest {

    // Saturday afternoon
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 14, 0);

    private static Integer dur(String text) {
        return TimeExpressionParser.parseDurationSeconds(text);
    }

    private static LocalDateTime clock(String text, LocalDateTime now) {
        return TimeExpressionParser.parseClockTime(text, now);
    }

    @Test
    public void simpleDurations() {
        assertEquals(Integer.valueOf(300), dur("set a timer for five minutes"));
        assertEquals(Integer.valueOf(60), dur("set a timer for a minute"));
        assertEquals(Integer.valueOf(90), dur("ninety seconds"));
        assertEquals(Integer.valueOf(120), dur("one hundred twenty seconds"));
        assertEquals(Integer.valueOf(1380), dur("twenty three minutes"));
        assertEquals(Integer.valueOf(600), dur("10 minutes"));
    }

    @Test
    public void compoundAndFractionalDurations() {
        assertEquals(Integer.valueOf(5400), dur("one hour and thirty minutes"));
        assertEquals(Integer.valueOf(5400), dur("an hour and a half"));
        assertEquals(Integer.valueOf(1800), dur("half an hour"));
        assertEquals(Integer.valueOf(1800), dur("a half hour"));
        assertEquals(Integer.valueOf(150), dur("two and a half minutes"));
        assertEquals(Integer.valueOf(3690), dur("one hour one minute thirty seconds"));
    }

    @Test
    public void garbledDurationsStillWork() {
        assertEquals(Integer.valueOf(300), dur("german for the time of five minute please"));
        assertEquals(Integer.valueOf(300), dur("they were for five minutes"));
    }

    @Test
    public void noDuration() {
        assertNull(dur("play some music"));
        assertNull(dur("set a timer"));
    }

    @Test
    public void ambiguousTimePicksSoonest() {
        assertEquals(LocalDateTime.of(2026, 10, 17, 19, 30), clock("set an alarm for seven thirty", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 18, 7, 0),
                clock("wake me up at seven", LocalDateTime.of(2026, 10, 17, 23, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 17, 15, 30), clock("alarm for half past three", NOW));
    }

    @Test
    public void explicitMeridiem() {
        assertEquals(LocalDateTime.of(2026, 10, 18, 6, 45), clock("set an alarm for six forty five am", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 17, 19, 5), clock("set an alarm for seven oh five p m", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 18, 7, 0), clock("alarm at seven in the morning", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 17, 21, 0), clock("alarm at nine o'clock tonight", NOW));
    }

    @Test
    public void relativeAndNamedTimes() {
        assertEquals(LocalDateTime.of(2026, 10, 18, 7, 45), clock("set an alarm for quarter to eight tomorrow", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 18, 12, 0), clock("alarm at noon tomorrow", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), clock("alarm at midnight", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 17, 18, 50), clock("alarm at ten to seven", NOW));
    }

    @Test
    public void durationIsNotAClockTime() {
        assertNull(clock("set an alarm for five minutes", NOW));
        assertNull(clock("set an alarm", NOW));
    }

    @Test
    public void describeDuration() {
        assertEquals("5 minutes", TimeExpressionParser.describeDuration(300));
        assertEquals("1 hour 30 minutes", TimeExpressionParser.describeDuration(5400));
        assertEquals("1 minute 30 seconds", TimeExpressionParser.describeDuration(90));
    }
}
