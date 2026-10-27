#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <VL53L1X.h>
#include "secrets.h"  // WIFI_SSID / WIFI_PASSWORD — gitignored, see include/secrets.example.h

// --- NETWORK CONFIG ---
// Packet format and server-side handling are specified in TRIPWIRE_PROTOCOL.md.
const char* ssid = WIFI_SSID;
const char* password = WIFI_PASSWORD;
const char* server_host = "eminich.com";
const int server_port = 3901;  // Not 3900: the wake-word listener drops foreign packets mid-command
const char* node_id = "bathroom_door";
const unsigned long WIFI_TIMEOUT_MS = 20000;      // Give up at boot and keep trying in the background
const unsigned long WIFI_RETRY_MS = 30000;
const unsigned long HEARTBEAT_MS = 30000;
// After a crossing, resend the snapshot at these offsets so one dropped packet costs ~200 ms,
// not a whole heartbeat. Every packet is a full snapshot, so repeats are harmless.
const unsigned long REPEAT_OFFSETS_MS[] = { 200, 1000 };
const int NUM_REPEATS = sizeof(REPEAT_OFFSETS_MS) / sizeof(REPEAT_OFFSETS_MS[0]);

WiFiUDP udp;

// --- ATOMS3 LITE PINS ---
#define I2C_SDA_PIN 2   // Grove PORT.A
#define I2C_SCL_PIN 1
#define LED_PIN     35  // Onboard WS2812

// --- TRIPWIRE CONFIG ---
// With USE_CALIBRATION, the baseline is measured at boot: keep the doorway clear for ~2 s after
// power-on. If calibration can't get a steady reading it falls back to DOORWAY_FIXED_MM.
// Set USE_CALIBRATION to false to hard-lock the baseline to DOORWAY_FIXED_MM.
const bool USE_CALIBRATION = false;
const int DOORWAY_FIXED_MM = 915;   // 3.0 ft
const int CAL_SAMPLES = 40;          // ~1.6 s at PERIOD_MS
const int CAL_MIN_VALID = 30;
const int CAL_MAX_SPREAD_MM = 60;    // Interquartile range above this means something moved through
const int CAL_MIN_MM = 150;
const int CAL_MAX_MM = 3500;

// A sample is "blocked" when the beam comes back TRIP_MARGIN_MM short of the baseline, and
// "clear" once it's back within CLEAR_MARGIN_MM (or finds no target at all). The gap between
// the two is hysteresis so a reading hovering at the threshold can't chatter.
const int TRIP_MARGIN_MM = 150;
const int CLEAR_MARGIN_MM = 75;
const int BLOCK_CONFIRM = 2;         // Consecutive blocked samples (~80 ms) to count a crossing
const int CLEAR_CONFIRM = 5;         // Consecutive clear samples (~200 ms) to re-arm

// Long mode reaches the far jamb comfortably indoors; in direct sunlight it can drop to ~70 cm,
// in which case Short mode (good to ~1.3 m in any light) is the better pick for a 3.5 ft door.
const VL53L1X::DistanceMode DISTANCE_MODE = VL53L1X::Long;
const unsigned long PERIOD_MS = 40;
const uint32_t TIMING_BUDGET_US = 33000;

const bool PRINT_READINGS = false;   // Every sample to serial (25/s); otherwise a status line every 5 s
const unsigned long STATUS_PRINT_MS = 5000;

VL53L1X tof;
int baseline_mm = DOORWAY_FIXED_MM;
const char* baseline_source = "fixed";

// Random per boot so the server can tell a reboot (count reset to 0) from a stale packet
uint32_t boot_id;
uint32_t crossing_count = 0;
bool blocked = false;
int block_streak = 0;
int clear_streak = 0;
int last_mm = -1;
int closest_mm = -1;  // Closest reading during the current/most recent block

unsigned long last_crossing_ms = 0;
int repeats_sent = NUM_REPEATS;
unsigned long last_send_ms = 0;
unsigned long last_wifi_retry_ms = 0;
unsigned long last_status_ms = 0;

void set_led(uint8_t r, uint8_t g, uint8_t b) {
    neopixelWrite(LED_PIN, r, g, b);
}

void connect_wifi() {
    Serial.printf("[*] Connecting to %s", ssid);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(ssid, password);
    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
        delay(500);
        Serial.print(".");
    }
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("\n[-] Wi-Fi not up yet. Tripwire runs anyway; will keep retrying.");
        return;
    }
    Serial.printf("\n[+] Connected! IP: %s  RSSI: %d dBm\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
}

void scan_i2c() {
    Serial.printf("[*] Scanning I2C on SDA=G%d SCL=G%d...\n", I2C_SDA_PIN, I2C_SCL_PIN);
    int found = 0;
    for (uint8_t addr = 1; addr < 127; addr++) {
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0) {
            Serial.printf("    found device at 0x%02X\n", addr);
            found++;
        }
    }
    if (found == 0) Serial.println("[-] No I2C devices found. Check the Grove cable.");
}

bool init_tof() {
    tof.setTimeout(500);
    if (!tof.init()) {
        Serial.println("[-] VL53L1X did not respond at 0x29.");
        return false;
    }
    tof.setDistanceMode(DISTANCE_MODE);
    tof.setMeasurementTimingBudget(TIMING_BUDGET_US);
    tof.startContinuous(PERIOD_MS);
    Serial.println("[+] VL53L1X ready.");
    return true;
}

// Returns distance in mm, or -1 when there's no valid target (timeout, out of range, etc.).
int read_tof_mm() {
    int mm = tof.read();
    if (tof.timeoutOccurred()) return -1;
    if (tof.ranging_data.range_status != VL53L1X::RangeValid) return -1;
    return mm;
}

int cmp_int(const void* a, const void* b) {
    return *(const int*)a - *(const int*)b;
}

void calibrate() {
    if (!USE_CALIBRATION) {
        Serial.printf("[*] Calibration disabled. Baseline locked to %d mm.\n", DOORWAY_FIXED_MM);
        return;
    }
    Serial.println("[*] Calibrating: keep the doorway clear...");
    set_led(0, 0, 20);

    int samples[CAL_SAMPLES];
    int valid = 0;
    for (int i = 0; i < CAL_SAMPLES; i++) {
        int mm = read_tof_mm();
        if (mm >= 0) samples[valid++] = mm;
    }
    if (valid < CAL_MIN_VALID) {
        Serial.printf("[-] Calibration failed: only %d/%d valid readings. Using fixed %d mm.\n",
                      valid, CAL_SAMPLES, DOORWAY_FIXED_MM);
        return;
    }

    qsort(samples, valid, sizeof(int), cmp_int);
    int median = samples[valid / 2];
    int spread = samples[valid * 3 / 4] - samples[valid / 4];
    if (spread > CAL_MAX_SPREAD_MM) {
        Serial.printf("[-] Calibration failed: readings unsteady (IQR %d mm). Using fixed %d mm.\n",
                      spread, DOORWAY_FIXED_MM);
        return;
    }
    if (median < CAL_MIN_MM || median > CAL_MAX_MM) {
        Serial.printf("[-] Calibration failed: %d mm is implausible. Using fixed %d mm.\n",
                      median, DOORWAY_FIXED_MM);
        return;
    }

    baseline_mm = median;
    baseline_source = "calibrated";
    Serial.printf("[+] Calibrated baseline: %d mm (%.1f in, IQR %d mm, %d/%d valid).\n",
                  median, median / 25.4, spread, valid, CAL_SAMPLES);
}

void send_snapshot(const char* reason) {
    last_send_ms = millis();
    if (WiFi.status() != WL_CONNECTED) return;

    char json[256];
    snprintf(json, sizeof(json),
             "{\"node\":\"%s\",\"type\":\"tripwire\",\"reason\":\"%s\",\"boot\":\"%08lx\","
             "\"count\":%lu,\"blocked\":%s,\"mm\":%d,\"baseline_mm\":%d,\"uptime_ms\":%lu}",
             node_id, reason, (unsigned long)boot_id, (unsigned long)crossing_count,
             blocked ? "true" : "false", closest_mm, baseline_mm, millis());

    if (!udp.beginPacket(server_host, server_port)) {
        Serial.printf("[-] Could not resolve %s; dropped %s packet.\n", server_host, reason);
        return;
    }
    udp.print(json);
    udp.endPacket();
}

void update_tripwire(int mm) {
    bool sample_blocked = mm >= 0 && mm < baseline_mm - TRIP_MARGIN_MM;
    bool sample_clear = mm < 0 || mm > baseline_mm - CLEAR_MARGIN_MM;

    if (!blocked) {
        block_streak = sample_blocked ? block_streak + 1 : 0;
        if (block_streak >= BLOCK_CONFIRM) {
            blocked = true;
            clear_streak = 0;
            closest_mm = mm;
            crossing_count++;
            Serial.printf("[!] Beam broken at %d mm. Crossing #%lu.\n", mm, (unsigned long)crossing_count);
            set_led(40, 0, 0);
            send_snapshot("cross");
            last_crossing_ms = millis();
            repeats_sent = 0;
        }
    } else {
        if (sample_blocked && mm < closest_mm) closest_mm = mm;
        clear_streak = sample_clear ? clear_streak + 1 : 0;
        if (clear_streak >= CLEAR_CONFIRM) {
            blocked = false;
            block_streak = 0;
            Serial.printf("[+] Beam clear (closest was %d mm).\n", closest_mm);
            set_led(0, 8, 0);
        }
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);  // Let the USB CDC port enumerate so the first lines aren't lost
    Serial.println("\n[*] Jarvis AtomS3 Lite - doorway tripwire");
    set_led(20, 20, 0);

    boot_id = esp_random();
    connect_wifi();

    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN, 400000);
    while (!init_tof()) {
        // Keep retrying so a reseated cable is picked up without a reflash
        set_led(40, 10, 0);
        scan_i2c();
        delay(5000);
    }

    calibrate();
    Serial.printf("[*] Armed: trips below %d mm, clears above %d mm (baseline %d mm, %s).\n",
                  baseline_mm - TRIP_MARGIN_MM, baseline_mm - CLEAR_MARGIN_MM,
                  baseline_mm, baseline_source);
    set_led(0, 8, 0);
    send_snapshot("boot");
}

void loop() {
    int mm = read_tof_mm();  // Blocks until the next measurement, pacing the loop at PERIOD_MS
    last_mm = mm;
    if (PRINT_READINGS) Serial.printf("%d mm\n", mm);
    update_tripwire(mm);

    unsigned long now = millis();
    if (repeats_sent < NUM_REPEATS && now - last_crossing_ms >= REPEAT_OFFSETS_MS[repeats_sent]) {
        send_snapshot("repeat");
        repeats_sent++;
    }
    if (now - last_send_ms >= HEARTBEAT_MS) {
        send_snapshot("heartbeat");
    }

    if (WiFi.status() != WL_CONNECTED && now - last_wifi_retry_ms >= WIFI_RETRY_MS) {
        last_wifi_retry_ms = now;
        Serial.println("[*] Wi-Fi down; reconnecting...");
        WiFi.reconnect();
    }

    if (!PRINT_READINGS && now - last_status_ms >= STATUS_PRINT_MS) {
        last_status_ms = now;
        Serial.printf("[STATUS] %d mm  %s  crossings=%lu  baseline=%d (%s)  wifi=%s\n",
                      last_mm, blocked ? "BLOCKED" : "clear", (unsigned long)crossing_count,
                      baseline_mm, baseline_source, WiFi.status() == WL_CONNECTED ? "up" : "down");
    }
}
