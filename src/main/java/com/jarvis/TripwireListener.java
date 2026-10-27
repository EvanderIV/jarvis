package com.jarvis;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Doorway tripwire nodes (ToF beam across a doorframe), one JSON datagram per packet on its own UDP port.
 * The device only reports that the doorway was crossed, so inside/outside is tracked here by parity of
 * the device's crossing count. See PlatformIO_Jarvis_M5Stack-ATOMS3LITE/TRIPWIRE_PROTOCOL.md.
 */
public class TripwireListener implements Runnable {

    public static final String BATHROOM = "bathroom_door";

    public enum Cause { CROSSING, OVERRIDE, DAILY_RESET }

    public interface PresenceListener {
        void onPresenceChanged(String node, boolean inside, Cause cause);
    }

    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(90); // three missed heartbeats
    // Every day at RESET_TIME, a node that hasn't been tripped within RESET_QUIET_PERIOD goes back to outside,
    // so a parity slip (half-crossing, steam, a missed packet) can't stick for more than a day.
    private static final LocalTime RESET_TIME = LocalTime.of(4, 0);
    private static final Duration RESET_QUIET_PERIOD = Duration.ofMinutes(30);

    private static final class NodeState {
        boolean inside = false;
        String boot;
        long count;
        int baselineMm = -1;
        Instant lastSeen;
        Instant lastTripped;   // last crossing or manual override
        Instant lastChanged;
    }

    private final int port;
    private final Clock clock;
    private final Gson gson = new Gson();
    private final Map<String, NodeState> nodes = new LinkedHashMap<>();
    private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TripwireReset");
        t.setDaemon(true);
        return t;
    });
    private volatile DatagramSocket socket;
    private volatile boolean running = true;

    public TripwireListener(int port) {
        this(port, Clock.systemDefaultZone());
    }

    TripwireListener(int port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    public void addListener(PresenceListener listener) {
        listeners.add(listener);
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
        } catch (SocketException e) {
            System.err.println("[-] Tripwire: Could not bind UDP port " + port + " (" + e.getMessage() + "). Room presence disabled.");
            return;
        }
        System.out.println("Listening for doorway tripwires on UDP port " + port);
        scheduleDailyReset();

        byte[] buffer = new byte[1024];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (!running) break;
                System.err.println("[-] Tripwire: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
        DatagramSocket s = socket;
        if (s != null) s.close();
    }

    /** Applies one packet. Malformed or foreign packets are logged and ignored. */
    void handlePacket(String json) {
        JsonObject p;
        String node, boot;
        long count;
        try {
            p = gson.fromJson(json, JsonObject.class);
            if (p == null || !"tripwire".equals(string(p, "type"))) {
                if (App.DEBUG_MODE) System.out.println("[DEBUG] Tripwire: ignoring non-tripwire packet: " + json);
                return;
            }
            node = string(p, "node");
            boot = string(p, "boot");
            count = p.get("count").getAsLong();
            if (node == null || boot == null || count < 0) throw new IllegalArgumentException("missing node/boot/count");
        } catch (Exception e) {
            System.err.println("[-] Tripwire: Discarding malformed packet (" + e.getMessage() + "): " + json);
            return;
        }

        Boolean changedTo = null;
        synchronized (this) {
            Instant now = clock.instant();
            NodeState s = nodes.computeIfAbsent(node, k -> new NodeState());
            s.lastSeen = now;
            if (p.has("baseline_mm")) s.baselineMm = p.get("baseline_mm").getAsInt();

            if (s.boot == null) {
                // First packet since the server started: crossings before it are unknowable, so adopt the count
                s.boot = boot;
                s.count = count;
                System.out.println("[+] Tripwire " + node + ": online (count " + count + "), assuming " + describe(s.inside));
                return;
            }
            if (!boot.equals(s.boot)) {
                System.out.println("[*] Tripwire " + node + ": device rebooted, staying " + describe(s.inside));
                s.boot = boot;
                s.count = 0;
            }
            if (count <= s.count) return; // repeat, heartbeat, or a stale duplicate

            long delta = count - s.count;
            s.count = count;
            s.lastTripped = now;
            if (delta > 1) {
                System.out.println("[!] Tripwire " + node + ": " + (delta - 1) + " crossing packet(s) lost; parity still applied");
            }
            if (delta % 2 == 1) {
                s.inside = !s.inside;
                s.lastChanged = now;
                changedTo = s.inside;
            }
            System.out.println("[+] Tripwire " + node + ": crossing #" + count + " → " + describe(s.inside).toUpperCase());
        }
        if (changedTo != null) notifyListeners(node, changedTo, Cause.CROSSING);
    }

    /** Manual correction. Leaves the crossing count alone so later crossings toggle from this value. */
    public void setInside(String node, boolean inside) {
        boolean changed;
        synchronized (this) {
            NodeState s = nodes.computeIfAbsent(node, k -> new NodeState());
            Instant now = clock.instant();
            s.lastTripped = now;
            changed = s.inside != inside;
            s.inside = inside;
            if (changed) s.lastChanged = now;
        }
        System.out.println("[+] Tripwire " + node + ": manually set " + describe(inside).toUpperCase());
        if (changed) notifyListeners(node, inside, Cause.OVERRIDE);
    }

    public synchronized boolean isInside(String node) {
        NodeState s = nodes.get(node);
        return s != null && s.inside;
    }

    public boolean isInBathroom() {
        return isInside(BATHROOM);
    }

    /** False if the node has never reported or has missed three heartbeats. Consumers can decline to act on stale data. */
    public synchronized boolean isOnline(String node) {
        NodeState s = nodes.get(node);
        return s != null && s.lastSeen != null
                && Duration.between(s.lastSeen, clock.instant()).compareTo(OFFLINE_AFTER) <= 0;
    }

    /** Sends every node that's still "inside" but quiet for RESET_QUIET_PERIOD back to outside. */
    void dailyReset() {
        List<String> reset = new ArrayList<>();
        synchronized (this) {
            Instant now = clock.instant();
            for (Map.Entry<String, NodeState> e : nodes.entrySet()) {
                NodeState s = e.getValue();
                boolean recentlyTripped = s.lastTripped != null
                        && Duration.between(s.lastTripped, now).compareTo(RESET_QUIET_PERIOD) < 0;
                if (s.inside && !recentlyTripped) {
                    s.inside = false;
                    s.lastChanged = now;
                    reset.add(e.getKey());
                }
            }
        }
        for (String node : reset) {
            System.out.println("[*] Tripwire " + node + ": daily reset → OUTSIDE");
            notifyListeners(node, false, Cause.DAILY_RESET);
        }
    }

    private void scheduleDailyReset() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime next = now.toLocalDate().atTime(RESET_TIME);
        if (!next.isAfter(now)) next = next.plusDays(1);
        // Rescheduled after each run rather than at a fixed rate, so DST changes don't drift it off 4:00
        scheduler.schedule(() -> {
            try {
                dailyReset();
            } catch (Exception e) {
                System.err.println("[-] Tripwire: Daily reset failed: " + e.getMessage());
            }
            scheduleDailyReset();
        }, Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One line per node for the admin console. */
    public synchronized List<String> describeNodes() {
        List<String> lines = new ArrayList<>();
        Instant now = clock.instant();
        for (Map.Entry<String, NodeState> e : nodes.entrySet()) {
            NodeState s = e.getValue();
            lines.add(String.format("%s: %s, %s, count=%d, boot=%s, baseline=%s, last change %s",
                    e.getKey(), describe(s.inside).toUpperCase(), isOnline(e.getKey()) ? "online" : "OFFLINE",
                    s.count, s.boot == null ? "-" : s.boot, s.baselineMm < 0 ? "-" : s.baselineMm + "mm",
                    s.lastChanged == null ? "never" : ago(Duration.between(s.lastChanged, now)) + " ago"));
        }
        return lines;
    }

    private void notifyListeners(String node, boolean inside, Cause cause) {
        for (PresenceListener l : listeners) {
            try {
                l.onPresenceChanged(node, inside, cause);
            } catch (Exception e) {
                System.err.println("[-] Tripwire: Listener failed: " + e.getMessage());
            }
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String describe(boolean inside) {
        return inside ? "inside" : "outside";
    }

    private static String ago(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }
}
