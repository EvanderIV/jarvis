package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class TripwireListenerTest {

    private static final String NODE = TripwireListener.BATHROOM;

    /** Clock the test can move forward. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-26T03:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final TripwireListener tripwire = new TripwireListener(0, clock);

    private static String packet(String boot, long count) {
        return "{\"node\":\"bathroom_door\",\"type\":\"tripwire\",\"reason\":\"cross\",\"boot\":\"" + boot
                + "\",\"count\":" + count + ",\"blocked\":true,\"mm\":400,\"baseline_mm\":1067,\"uptime_ms\":9000,\"future\":1}";
    }

    /** Feeds "A:0", "B:1"... and returns the resulting state. */
    private boolean feed(String... packets) {
        for (String p : packets) {
            String[] parts = p.split(":");
            tripwire.handlePacket(packet(parts[0], Long.parseLong(parts[1])));
        }
        return tripwire.isInBathroom();
    }

    @Test
    public void followsTheProtocolTable() {
        assertTrue(new TripwireListenerTest().feed("A:0", "A:1"));
        assertTrue(new TripwireListenerTest().feed("A:0", "A:1", "A:1", "A:1"));
        assertFalse(new TripwireListenerTest().feed("A:0", "A:2"));
        assertTrue(new TripwireListenerTest().feed("A:0", "A:3"));
        assertTrue(new TripwireListenerTest().feed("A:0", "A:1", "B:0"));
        assertFalse(new TripwireListenerTest().feed("A:0", "A:1", "B:1"));
        assertFalse(new TripwireListenerTest().feed("A:5"));
        assertTrue(new TripwireListenerTest().feed("A:0", "A:1", "A:0"));
    }

    @Test
    public void defaultsToOutsideAndIgnoresJunk() {
        assertFalse(tripwire.isInBathroom());
        assertFalse(tripwire.isOnline(NODE));
        feed("A:0", "A:1");
        tripwire.handlePacket("not json");
        tripwire.handlePacket("{\"node\":\"bathroom_door\",\"type\":\"wakeword\",\"boot\":\"A\",\"count\":2}");
        tripwire.handlePacket("{\"node\":\"bathroom_door\",\"type\":\"tripwire\"}");
        tripwire.handlePacket("");
        assertTrue(tripwire.isInBathroom());
        assertTrue(tripwire.isOnline(NODE));
        clock.advance(Duration.ofSeconds(91));
        assertFalse(tripwire.isOnline(NODE));
        assertTrue("stale nodes keep their last value", tripwire.isInBathroom());
    }

    @Test
    public void overrideCorrectsWithoutTouchingTheCount() {
        List<String> events = new ArrayList<>();
        tripwire.addListener((node, inside, cause) -> events.add(inside + "/" + cause));
        feed("A:0", "A:1");
        tripwire.setInside(NODE, false);
        feed("A:1");
        assertFalse(tripwire.isInBathroom());
        feed("A:2");
        assertTrue(tripwire.isInBathroom());
        assertEquals(List.of("true/CROSSING", "false/OVERRIDE", "true/CROSSING"), events);
    }

    @Test
    public void dailyResetOnlyClearsQuietNodes() {
        List<String> events = new ArrayList<>();
        tripwire.addListener((node, inside, cause) -> events.add(inside + "/" + cause));

        feed("A:0", "A:1");
        clock.advance(Duration.ofMinutes(10));
        tripwire.dailyReset();
        assertTrue("tripped 10 minutes ago, so the reset is skipped", tripwire.isInBathroom());

        clock.advance(Duration.ofMinutes(25));
        tripwire.dailyReset();
        assertFalse(tripwire.isInBathroom());
        assertEquals(List.of("true/CROSSING", "false/DAILY_RESET"), events);

        // Heartbeats aren't trips: the next crossing still toggles from the reset value
        feed("A:1", "A:1", "A:2");
        assertTrue(tripwire.isInBathroom());
    }
}
