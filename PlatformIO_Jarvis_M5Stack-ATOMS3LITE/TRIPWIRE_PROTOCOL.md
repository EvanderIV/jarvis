# Doorway Tripwire → Jarvis: Protocol & Backend Spec

**Audience:** an agent implementing the server side in the Jarvis Java backend (`src/main/java/com/jarvis/`).
**Device firmware:** `PlatformIO_Jarvis_M5Stack-ATOMS3LITE/src/main.cpp` (already implemented and verified on hardware).

## Goal

An AtomS3 Lite with a VL53L1X time-of-flight sensor is mounted on a doorframe. Its beam runs across the doorway to the opposite jamb. Each time someone walks through the doorway the beam is broken, and the device reports the crossing over UDP.

The backend must keep a **boolean "the user is inside the watched room"**, which is the bathroom. The user wants Jarvis to know when they are out of the shower in the morning. So the boolean, and especially the moment it flips from inside to outside, is what other Jarvis features will consume.

## What the device can and cannot tell you

- It reports **that** the doorway was crossed, never **which way**. A single beam has no direction.
- Inside/outside is therefore derived by **parity**. Each crossing flips the state, and the server owns that state. The device keeps no notion of inside or outside.
- The device counts crossings since its own boot. Every packet carries that running count, so the server can recover the correct parity even when packets are lost (see the algorithm below).

## Transport

| | |
|---|---|
| Protocol | UDP, one JSON object per datagram, UTF-8, < 300 bytes |
| Destination | `eminich.com:3901`, the same host as the wake-word nodes |
| Direction | Device → server only. **The server must not reply.** The device never reads its socket. |

**Use a new socket on port 3901. Do not route this through the existing port-3900 `UdpListener`.** That listener is single-threaded. While it captures a voice command it drops every packet that doesn't come from the streaming node (`UdpListener.java`, the address/port check inside the audio loop). Its outer loop also only accepts JSON containing `"amplitude"`. Tripwire packets sent there would be lost during commands.

Operational note for the user, not something the agent can do: the device resolves `eminich.com` to the public IP. UDP 3901 needs the same router port-forward (and NAT hairpin) that 3900 already has, plus a host firewall rule if one is in use. Until then, packets won't arrive.

## Packet format

Every packet is a **complete snapshot** of the device's state, with the same shape whatever the reason it was sent.

```json
{"node":"bathroom_door","type":"tripwire","reason":"cross","boot":"99acf80d","count":1,"blocked":true,"mm":270,"baseline_mm":1067,"uptime_ms":5859}
```

| Field | Type | Meaning |
|---|---|---|
| `node` | string | Device ID. Currently always `"bathroom_door"`. Key all state by this so a second sensor can be added later. |
| `type` | string | Always `"tripwire"`. Reject anything else. |
| `reason` | string | Why this packet was sent: `boot`, `cross`, `repeat`, or `heartbeat`. **Informational only.** The state algorithm must not branch on it. |
| `boot` | string | 8 hex chars, random per device boot. A change means the device rebooted and `count` restarted from 0. Compare for equality only. |
| `count` | integer ≥ 0 | Crossings since this boot. Monotonic within one `boot`. |
| `blocked` | bool | The beam is broken right now, for example someone standing in the doorway. Diagnostic only. **Not** "inside". |
| `mm` | integer | Closest distance measured during the current or most recent crossing; `-1` if there hasn't been one this boot. Diagnostic. |
| `baseline_mm` | integer | Distance across the doorway when clear: calibrated at boot, or the fixed 1067 mm (3.5 ft) fallback. Diagnostic. |
| `uptime_ms` | integer | Device `millis()`. Diagnostic. |

Ignore unknown fields so the firmware can add fields without breaking the server.

### Send schedule

| Reason | When |
|---|---|
| `boot` | Once, after Wi-Fi is up and the sensor is calibrated (`count` = 0) |
| `cross` | Immediately when the beam is broken (about 80 ms after the person enters it) |
| `repeat` | The same snapshot again at +200 ms and +1000 ms after each crossing, to cover packet loss |
| `heartbeat` | Whenever 30 s have passed since the last packet of any kind |

Within one boot, packets are idempotent. Receiving the same `count` several times is normal and expected.

Real captured sequence, from a hardware test where the sensor was forced to see a crossing:

```
+11.34s {"node":"bathroom_door","type":"tripwire","reason":"boot","boot":"99acf80d","count":0,"blocked":false,"mm":-1,"baseline_mm":1067,"uptime_ms":5747}
+11.45s {"node":"bathroom_door","type":"tripwire","reason":"cross","boot":"99acf80d","count":1,"blocked":true,"mm":270,"baseline_mm":1067,"uptime_ms":5859}
+11.68s {"node":"bathroom_door","type":"tripwire","reason":"repeat","boot":"99acf80d","count":1,"blocked":true,"mm":265,"baseline_mm":1067,"uptime_ms":6083}
+12.46s {"node":"bathroom_door","type":"tripwire","reason":"repeat","boot":"99acf80d","count":1,"blocked":true,"mm":265,"baseline_mm":1067,"uptime_ms":6866}
+42.48s {"node":"bathroom_door","type":"tripwire","reason":"heartbeat","boot":"99acf80d","count":1,"blocked":true,"mm":265,"baseline_mm":1067,"uptime_ms":36891}
```

## Server state algorithm

Per node, keep `inside` (boolean, default `false`), `boot` (string, initially null), `count` (long), and `lastSeen` (timestamp).

```
on packet p (valid JSON, type == "tripwire"):
    s = state[p.node]            // create on first sight, inside = false
    s.lastSeen = now

    if s.boot == null:            // first packet since the SERVER started
        s.boot = p.boot           // adopt the device's count as the reference point;
        s.count = p.count         // crossings before this are unknowable, so don't toggle
        return

    if p.boot != s.boot:          // the DEVICE rebooted: its count restarted at 0
        s.boot = p.boot
        s.count = 0

    if p.count <= s.count:        // repeat / heartbeat / reordered duplicate
        return

    delta = p.count - s.count
    s.count = p.count
    if delta > 1: log a warning (packets were lost; parity still correct)
    if delta is odd:
        s.inside = !s.inside
        notify listeners (node, inside, timestamp)
```

Properties this gives you, and which tests should check:

- Duplicates and repeats never double-toggle.
- If the `cross` packet and both repeats are lost, the next heartbeat (≤ 30 s later) still applies the toggle.
- If two crossings happen and only the second packet arrives, `delta` = 2 and the state is correctly unchanged.
- A device reboot does not change `inside`. The device holds no state, so the server's last value stands.
- A server restart resets `inside` to `false`. Persisting it is optional and not required for v1.

**Liveness:** treat a node as offline when `now - lastSeen > 90 s` (three missed heartbeats). Keep the last `inside` value, but expose the online flag so consumers can decline to act on stale data.

## Known limitations that consumers must tolerate

Parity can drift out of sync with reality. Each case below leaves the boolean inverted until it is corrected:

- **Half-crossings:** the user leans or steps into the doorway and backs out. That counts as 1 crossing, the same as walking through.
- **Missed crossings:** the person passes in under about 80 ms, or isn't seen, for example dark fabric at the edge of range. Unlikely, but possible.
- **False crossings:** another person or a pet, the door swinging through the beam (depends on which side it's mounted), or fog or droplets on the sensor window from shower steam.
- Lingering in the doorway counts as one crossing, which is correct. `blocked` stays `true` meanwhile.

Because of this, **provide a manual override**: set the state explicitly without touching `count`, so later crossings toggle from the corrected value. At minimum, add an admin console command (see below). A voice command such as "Jarvis, I'm out of the bathroom" would also fit the existing intent system, but it is optional.

## Suggested integration (follow existing patterns)

- A new `TripwireListener implements Runnable` owning its own `DatagramSocket(3901)`, started as a daemon thread in `App.main` alongside `NestListener`. Parse with Gson, which is already a dependency. Discard malformed packets with a log line; never let one exception kill the loop.
- State is written on the listener thread and read from routine, timer and command threads, so make it thread-safe (`synchronized` methods or atomics).
- Public API, roughly: `boolean isInside(String node)`, `boolean isOnline(String node)`, `void setInside(String node, boolean inside)` (the override), and `addListener(...)` for transitions. The user's actual use is reacting to the **inside → outside** transition in the morning, so a listener matters more than polling.
- Admin console (`App.java`'s command loop), and add both to `help`:
  - `room`: print each node's inside/outside, online/offline, `count`, `boot`, `baseline_mm`, and time since the last change
  - `room <in|out>`: manual override
- Log transitions the same way the rest of the server does, for example `[+] Tripwire bathroom_door: crossing #5 → INSIDE`.

## Testing without the hardware

Spoof packets from any machine that can reach the server:

```bash
send() { printf '%s' "$1" | nc -u -w0 127.0.0.1 3901; }
send '{"node":"bathroom_door","type":"tripwire","reason":"boot","boot":"aaaa0001","count":0,"blocked":false,"mm":-1,"baseline_mm":1067,"uptime_ms":5000}'
send '{"node":"bathroom_door","type":"tripwire","reason":"cross","boot":"aaaa0001","count":1,"blocked":true,"mm":400,"baseline_mm":1067,"uptime_ms":9000}'
```

Cases to cover, from server start with `inside = false`:

| Packets (boot:count) | Expected `inside` |
|---|---|
| A:0, A:1 | true |
| A:0, A:1, A:1, A:1 (repeats) | true |
| A:0, A:2 (one crossing lost) | false, plus a warning |
| A:0, A:3 | true |
| A:0, A:1, B:0 (reboot) | true |
| A:0, A:1, B:1 (reboot, then a crossing whose boot packet was lost) | false |
| A:5 as the first packet after server start | false (adopted, no toggle) |
| A:0, A:1, A:0 (stale) | true |
| Malformed JSON / `type` ≠ `tripwire` | unchanged, no crash |

## Out of scope

- Direction detection. A future firmware could split the sensor's field of view into two zones and add a direction field. The server should ignore unknown fields until then.
- Any change to the port-3900 wake-word path.
- Sending anything back to the device.
