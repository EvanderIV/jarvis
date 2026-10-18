#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <driver/i2s.h>
#include <math.h>
#include <FastLED.h>
#include "secrets.h"  // WIFI_SSID / WIFI_PASSWORD — gitignored, see include/secrets.example.h

// --- DEBUG / OFFLINE MODE ---
// Set to true to skip Wi-Fi and UDP. It will emulate winning arbitration locally
// and skip network streaming. Great for testing the mic sensitivity, LED, and speaker!
const bool DEBUG_OFFLINE_MODE = false;

// --- NETWORK CONFIG ---
const char* ssid = WIFI_SSID;
const char* password = WIFI_PASSWORD;
const char* server_ip = "eminich.com";
const int server_port = 3900;
const char* node_id = "atom_echo_1";

WiFiUDP udp;

// --- M5STACK ATOM ECHO MIC PINS ---
#define CONFIG_I2S_BCK_PIN 32
#define CONFIG_I2S_LRCK_PIN 33
#define CONFIG_I2S_DATA_PIN 34

// --- AUDIO CONFIG ---
#define SAMPLE_RATE 16000
#define BUFFER_SIZE 1024
uint8_t audio_buffer[BUFFER_SIZE];

// --- CAPTURE / DECIMATION CONFIG ---
// The ESP32's PDM→PCM converter only delivers clean audio up to ~1/4 of the configured rate;
// above that it mirrors the signal (a tone at f also shows up at rate/2 − f). At 16 kHz that
// folded everything above 4 kHz into a swirly "low-bitrate" image. So the mic runs at 32 kHz,
// where the mirror sits above 8 kHz, and a windowed-sinc low-pass at 8 kHz removes it before
// every other sample is dropped. Output is the same 512-sample 16 kHz chunks as before.
#define CAPTURE_RATE 32000
#define DECIM_TAPS   95  // Blackman window: passband to ~7.1 kHz, ≥74 dB stopband from ~8.9 kHz
int16_t capture_buffer[BUFFER_SIZE];  // 1024 samples @ 32 kHz → 512 samples @ 16 kHz
static float decim_taps[DECIM_TAPS];
static float decim_hist[DECIM_TAPS - 1 + BUFFER_SIZE];  // previous chunk's tail + current chunk

// --- PRE-ROLL BUFFER CONFIG ---
// 16000Hz * 16-bit Mono = 32,000 bytes per second.
// 32 chunks of 1024 bytes = ~1.024 seconds of historical audio context!
const int PRE_ROLL_CHUNKS = 32; 
uint8_t ring_buffer[PRE_ROLL_CHUNKS][BUFFER_SIZE];
int ring_head = 0;
int chunks_in_buffer = 0;

// --- LED CONFIG ---
#define LED_PIN 27
#define NUM_LEDS 1
CRGB leds[NUM_LEDS];

// --- VAD (Voice Activity) CONFIG ---
// Adjust these if the mic triggers too easily or not easily enough!
const double TRIGGER_THRESHOLD = 370.0;
const double SILENCE_THRESHOLD = 280.0;
const int MAX_SILENCE_CHUNKS = 30; // Stop streaming after ~1.5 seconds of silence

// --- HIGH-PASS FILTER CONFIG ---
// 2nd-order Butterworth HPF at 80 Hz, fs=16000 Hz (bilinear transform, Q=1/√2).
// −12 dB/oct rolloff vs the old 1st-order −6 dB/oct. Cuts rumble below 80 Hz
// while preserving vowel fundamentals (100–300 Hz) that the 150 Hz 1st-order was trimming.
// H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)
// y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] − a1·y[n-1] − a2·y[n-2]
const float HP_B0 =  0.97800f;
const float HP_B1 = -1.95600f;
const float HP_B2 =  0.97800f;
const float HP_A1 = -1.95553f;
const float HP_A2 =  0.95649f;
static float hp_x1 = 0.0f, hp_x2 = 0.0f;
static float hp_y1 = 0.0f, hp_y2 = 0.0f;

// --- HIGH-SHELF CONFIG ---
// RBJ-cookbook high shelf (S=1). Lifts the consonant band (s, t, k, sh) by SHELF_GAIN_DB
// while leaving vowels untouched. Replaces the old 0.97 first-difference pre-emphasis,
// which is really a ~30 dB low cut rather than a shelf, and which Vosk's MFCC front end
// already applies itself — stacking two of them tilted the spectrum far from what the
// model was trained on. Coefficients are computed in init_high_shelf().
const float SHELF_FREQ_HZ = 2500.0f;
const float SHELF_GAIN_DB = 6.0f;
static float hs_b0, hs_b1, hs_b2, hs_a1, hs_a2;
static float hs_x1 = 0.0f, hs_x2 = 0.0f;
static float hs_y1 = 0.0f, hs_y2 = 0.0f;

// --- AGC CONFIG ---
// Dynamically adjusts gain each chunk to hit a target RMS, keeping speech loud
// regardless of mic distance. Alpha controls adaptation speed: higher = slower,
// smoother (less pumping). Initial gain of 3.0 avoids a silent first chunk.
// The AGC only adapts while someone is talking (rms > SILENCE_THRESHOLD); during silence it
// holds, so it can't crank the noise floor up to max gain and then clip the next wake word.
const float AGC_TARGET_RMS = 3000.0f;
const float AGC_MAX_GAIN   = 10.0f;
const float AGC_MIN_GAIN   = 1.0f;
const float AGC_ALPHA      = 0.95f;
static float agc_gain      = 3.0f;

// --- OUTPUT GAIN CONFIG ---
// Final fixed boost applied to the samples that get sent to the server. The VAD RMS is
// measured before any gain, so the VAD thresholds, the LED brightness map, and the
// "amplitude" field in the trigger JSON all still report the true acoustic level.
// Purely a transmit-side level change so Vosk receives a hotter signal.
const float OUTPUT_GAIN = 2.0f;

// --- PEAK LIMITER CONFIG ---
// The total gain (AGC × OUTPUT_GAIN) is capped per chunk so the chunk's loudest sample lands
// at OUTPUT_CEILING instead of hard-clipping. Since the whole chunk is in memory, this is
// look-ahead limiting: gain drops before the peak, not after. Gain reductions are instant;
// recovery is limited to GAIN_RELEASE_PER_CHUNK (~1 dB per 32 ms chunk) to avoid pumping.
const float OUTPUT_CEILING         = 29000.0f;  // ≈ −1 dBFS
const float GAIN_RELEASE_PER_CHUNK = 1.12f;
static float applied_gain          = 3.0f * OUTPUT_GAIN;

// Float working buffer: the whole chain runs in float and is quantized to int16 once at the end.
static float work_buffer[BUFFER_SIZE / 2];

enum State { LISTENING, WAITING_ACK, STREAMING };
State currentState = LISTENING;
int silence_counter = 0;
unsigned long stream_start_time = 0;
unsigned long last_debug_print = 0;

void setup_i2s() {
    Serial.println("[*] Initializing ATOM Echo I2S Microphone...");
    
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM), // ATOM uses PDM mic
        .sample_rate = CAPTURE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 8,   // INCREASED: Gives the ESP32 more hardware headroom
        .dma_buf_len = 1024,  // INCREASED: Prevents audio dropping while flushing the pre-roll
        .use_apll = true      // APLL eliminates APB clock jitter at PDM rates → cleaner high-freq
    };

    i2s_pin_config_t pin_config = {
        .bck_io_num = CONFIG_I2S_BCK_PIN,
        .ws_io_num = CONFIG_I2S_LRCK_PIN,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num = CONFIG_I2S_DATA_PIN
    };

    i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL);
    i2s_set_pin(I2S_NUM_0, &pin_config);
    i2s_set_clk(I2S_NUM_0, CAPTURE_RATE, I2S_BITS_PER_SAMPLE_16BIT, I2S_CHANNEL_MONO);
}

void init_decimator() {
    // Half-band windowed sinc: cutoff at CAPTURE_RATE / 4 = 8 kHz, normalized to unity DC gain
    const int M = DECIM_TAPS - 1;
    float sum = 0.0f;
    for (int i = 0; i < DECIM_TAPS; i++) {
        float t    = i - M / 2.0f;
        float sinc = (t == 0.0f) ? 1.0f : sinf(PI * 0.5f * t) / (PI * 0.5f * t);
        float win  = 0.42f - 0.5f * cosf(2.0f * PI * i / M) + 0.08f * cosf(4.0f * PI * i / M);
        decim_taps[i] = sinc * win;
        sum += decim_taps[i];
    }
    for (int i = 0; i < DECIM_TAPS; i++) decim_taps[i] /= sum;
}

// Low-passes `n_in` samples at CAPTURE_RATE and keeps every other one, writing SAMPLE_RATE
// samples to `out`. Returns the number of output bytes.
size_t decimate(const int16_t* in, int n_in, int16_t* out) {
    const int H = DECIM_TAPS - 1;
    for (int i = 0; i < n_in; i++) decim_hist[H + i] = in[i];
    int n_out = n_in / 2;
    for (int j = 0; j < n_out; j++) {
        const float* x = &decim_hist[2 * j];
        float acc = 0.0f;
        for (int k = 0; k < DECIM_TAPS; k++) acc += decim_taps[k] * x[k];
        if (acc >  32767.0f) acc =  32767.0f;
        if (acc < -32768.0f) acc = -32768.0f;
        out[j] = (int16_t)lrintf(acc);
    }
    memmove(decim_hist, &decim_hist[n_in], H * sizeof(float));
    return n_out * 2;
}

void init_high_shelf() {
    float A     = powf(10.0f, SHELF_GAIN_DB / 40.0f);
    float w0    = 2.0f * PI * SHELF_FREQ_HZ / SAMPLE_RATE;
    float cw    = cosf(w0);
    float alpha = sinf(w0) / 2.0f * sqrtf(2.0f);  // shelf slope S = 1
    float sA    = 2.0f * sqrtf(A) * alpha;
    float a0    = (A + 1) - (A - 1) * cw + sA;
    hs_b0 =  A * ((A + 1) + (A - 1) * cw + sA) / a0;
    hs_b1 = -2 * A * ((A - 1) + (A + 1) * cw) / a0;
    hs_b2 =  A * ((A + 1) + (A - 1) * cw - sA) / a0;
    hs_a1 =  2 * ((A - 1) - (A + 1) * cw) / a0;
    hs_a2 =  ((A + 1) - (A - 1) * cw - sA) / a0;
}

void flash_init_led() {
    Serial.println("[*] Flashing INIT Visual Feedback...");
    
    // Quick flash of bright purple
    leds[0] = CRGB::Purple;
    FastLED.setBrightness(63);
    FastLED.show();
    delay(500);
    
    // FastLED.setBrightness(0);
    // FastLED.show();
    // delay(100);
    
    // Restore default dim blue glow
    // leds[0] = CRGB(0, 150, 255);
    // FastLED.setBrightness(5);
    // FastLED.show();
}

void flash_ready_led() {
    Serial.println("[*] Flashing INIT Visual Feedback...");
    
    // Quick flash of bright white
    leds[0] = CRGB::White;
    FastLED.setBrightness(255);
    FastLED.show();
    delay(500);
    
    FastLED.setBrightness(0);
    FastLED.show();
    delay(100);
    
    // Restore default dim blue glow
    leds[0] = CRGB(0, 150, 255);
    FastLED.setBrightness(5);
    FastLED.show();
}

void flash_eof_led() {
    Serial.println("[*] Flashing EOF Visual Feedback...");
    
    // Quick triple flash of bright green
    for (int i = 0; i < 3; i++) {
        leds[0] = CRGB::Green;
        FastLED.setBrightness(255);
        FastLED.show();
        delay(50);
        
        FastLED.setBrightness(0);
        FastLED.show();
        delay(100);
    }
    
    // Restore default dim blue glow
    leds[0] = CRGB(0, 150, 255);
    FastLED.setBrightness(5);
    FastLED.show();
}

void flash_arbfail_led() {
    Serial.println("[*] Flashing ARBFAIL Visual Feedback...");
    
    // Quick double flash of bright orange
    for (int i = 0; i < 2; i++) {
        leds[0] = CRGB::Orange;
        FastLED.setBrightness(255);
        FastLED.show();
        delay(100);
        
        FastLED.setBrightness(0);
        FastLED.show();
        delay(100);
    }
    
    // Restore default dim blue glow
    leds[0] = CRGB(0, 150, 255);
    FastLED.setBrightness(5);
    FastLED.show();
}

void setup() {
    Serial.begin(115200);

    // --- THERMAL MANAGEMENT ---
    // Downclock CPU from 240MHz to 80MHz to drastically reduce heat and power draw
    setCpuFrequencyMhz(80); 
    Serial.printf("[*] CPU Frequency set to: %d MHz\n", getCpuFrequencyMhz());

    // 0. Init LED
    FastLED.addLeds<WS2812, LED_PIN, GRB>(leds, NUM_LEDS);
    leds[0] = CRGB::Red; // Power LED
    FastLED.setBrightness(255);
    FastLED.show();

    delay(1000);
    
    if (!DEBUG_OFFLINE_MODE) {
        // 1. Connect to Wi-Fi
        Serial.printf("\n[*] Connecting to %s", ssid);
        WiFi.begin(ssid, password);
        while (WiFi.status() != WL_CONNECTED) {
            delay(500);
            Serial.print(".");
        }
        Serial.printf("\n[+] Connected! IP: %s\n", WiFi.localIP().toString().c_str());

        // --- THERMAL MANAGEMENT 2 ---
        // Reduce Wi-Fi TX power from default ~19.5dBm to 8.5dBm. 
        // This is perfectly fine for indoor apartment range and drastically lowers LDO heat.
        WiFi.setTxPower(WIFI_POWER_8_5dBm);
        Serial.println("[*] Wi-Fi TX Power throttled to 8.5dBm to reduce heat.");

        flash_init_led();

        delay(500);

        // 3. Init UDP
        udp.begin(server_port);
    } else {
        Serial.println("\n[!] RUNNING IN DEBUG_OFFLINE_MODE. Initializing network diagnostics...");
        
        // 1. Connect to Wi-Fi for diagnostics
        Serial.printf("[*] Connecting to %s", ssid);
        WiFi.begin(ssid, password);
        while (WiFi.status() != WL_CONNECTED) {
            delay(500);
            Serial.print(".");
        }
        Serial.printf("\n[+] Connected! Local IP: %s\n", WiFi.localIP().toString().c_str());
        
        // 2. Test DNS Resolution
        Serial.println("[DEBUG] Testing DNS Resolution...");
        IPAddress resolved_ip;
        if (WiFi.hostByName(server_ip, resolved_ip)) {
            Serial.printf("[+] Successfully resolved '%s' to IP: %s\n", server_ip, resolved_ip.toString().c_str());
        } else {
            Serial.printf("[-] DNS ERROR: Could not resolve '%s'. Check domain name.\n", server_ip);
        }
        
        // 3. Send Diagnostic UDP Ping
        Serial.println("[DEBUG] Sending diagnostic ping (UDP Trigger) to server...");
        udp.begin(server_port);
        char json_payload[100];
        snprintf(json_payload, sizeof(json_payload), "{\"node\":\"%s\",\"amplitude\":%.1f}", node_id, 999.9);
        udp.beginPacket(server_ip, server_port);
        udp.print(json_payload);
        udp.endPacket();
        
        // 4. Wait for Java Server ACK
        Serial.println("[DEBUG] Waiting 5 seconds for Java Server ACK...");
        unsigned long start_wait = millis();
        bool got_ack = false;
        while (millis() - start_wait < 5000) {
            int packetSize = udp.parsePacket();
            if (packetSize) {
                char ack_buffer[255];
                int len = udp.read(ack_buffer, 255);
                if (len > 0) ack_buffer[len] = '\0';
                if (String(ack_buffer) == "ACK_START_STREAM") {
                    got_ack = true;
                    break;
                }
            }
            delay(10);
        }
        
        if (got_ack) {
            Serial.println("[+] SUCCESS! Received ACK from Java server. Two-way communication is working.");
        } else {
            Serial.println("[-] FAILED: No response from Java server.");
            Serial.println("    -> Is the Java server running?");
            Serial.printf("    -> Is the server listening on port %d?\n", server_port);
            Serial.println("    -> Are you blocking UDP traffic in your firewall?");
            Serial.println("    -> Is NAT Hairpinning failing for your domain?");
        }
        
        Serial.println("\n[!] Diagnostics complete. Halting.");
        while(true) { delay(1000); } // Halt so it doesn't run the normal loop
    }

    // 2. Init Microphone
    init_decimator();
    init_high_shelf();
    setup_i2s();

    // Drain the PDM mic's DMA buffers for 500ms — the first reads after i2s_driver_install
    // contain hardware initialization noise that would otherwise immediately blast a false trigger.
    Serial.println("[*] Draining I2S startup noise...");
    size_t dummy_bytes;
    unsigned long drain_start = millis();
    while (millis() - drain_start < 500) {
        i2s_read(I2S_NUM_0, &audio_buffer, BUFFER_SIZE, &dummy_bytes, portMAX_DELAY);
    }
    i2s_zero_dma_buffer(I2S_NUM_0);

    flash_ready_led();

    Serial.println("[+] Satellite is LIVE. Listening for noise...");
}

// Runs HPF → high shelf → AGC/output gain (peak-limited) in float, then writes the result
// back into `buffer` as int16. Returns the post-HPF, pre-gain RMS used by the VAD.
double process_audio(uint8_t* buffer, size_t bytes_read) {
    int16_t* samples = (int16_t*)buffer;
    int num_samples = bytes_read / 2;

    // 1. High-pass + VAD level (same measurement point as before, so thresholds stay calibrated)
    double sum = 0;
    for (int i = 0; i < num_samples; i++) {
        float x = (float)samples[i];
        float y = HP_B0*x + HP_B1*hp_x1 + HP_B2*hp_x2 - HP_A1*hp_y1 - HP_A2*hp_y2;
        hp_x2 = hp_x1;  hp_x1 = x;
        hp_y2 = hp_y1;  hp_y1 = y;
        work_buffer[i] = y;
        sum += (double)y * y;
    }
    double rms = sqrt(sum / num_samples);

    // 2. High shelf, tracking the chunk peak for the limiter
    float peak = 1.0f;
    for (int i = 0; i < num_samples; i++) {
        float x = work_buffer[i];
        float y = hs_b0*x + hs_b1*hs_x1 + hs_b2*hs_x2 - hs_a1*hs_y1 - hs_a2*hs_y2;
        hs_x2 = hs_x1;  hs_x1 = x;
        hs_y2 = hs_y1;  hs_y1 = y;
        work_buffer[i] = y;
        float mag = fabsf(y);
        if (mag > peak) peak = mag;
    }

    // 3. AGC (adapts only during speech) × fixed output gain, capped by the peak limiter
    if (rms > SILENCE_THRESHOLD) {
        float desired = AGC_TARGET_RMS / (float)rms;
        desired = constrain(desired, AGC_MIN_GAIN, AGC_MAX_GAIN);
        agc_gain = AGC_ALPHA * agc_gain + (1.0f - AGC_ALPHA) * desired;
    }
    float target_gain = fminf(agc_gain * OUTPUT_GAIN, OUTPUT_CEILING / peak);

    float start_gain;
    if (target_gain <= applied_gain) {
        // Attack: drop immediately for this whole chunk so the peak can't clip
        start_gain = target_gain;
    } else {
        // Release: ramp up across the chunk; every sample's gain stays <= target_gain
        start_gain = applied_gain;
        target_gain = fminf(target_gain, applied_gain * GAIN_RELEASE_PER_CHUNK);
    }
    float gain_step = (target_gain - start_gain) / num_samples;

    // 4. Single quantization back to int16
    float g = start_gain;
    for (int i = 0; i < num_samples; i++) {
        g += gain_step;
        float y = work_buffer[i] * g;
        if (y >  32767.0f) y =  32767.0f;
        if (y < -32768.0f) y = -32768.0f;
        samples[i] = (int16_t)lrintf(y);
    }
    applied_gain = target_gain;

    return rms;
}

void loop() {
    size_t capture_bytes;
    i2s_read(I2S_NUM_0, capture_buffer, sizeof(capture_buffer), &capture_bytes, portMAX_DELAY);

    if (capture_bytes < 4) return;
    size_t bytes_read = decimate(capture_buffer, (capture_bytes / 2) & ~1, (int16_t*)audio_buffer);

    double rms = process_audio(audio_buffer, bytes_read);  // rms is pre-gain so VAD thresholds remain calibrated

    // --- PRE-ROLL MEMORY ---
    // Constantly record the last ~1 second of audio so the start of the wake word isn't clipped
    if (currentState == LISTENING || currentState == WAITING_ACK) {
        memcpy(ring_buffer[ring_head], audio_buffer, BUFFER_SIZE);
        ring_head = (ring_head + 1) % PRE_ROLL_CHUNKS;
        if (chunks_in_buffer < PRE_ROLL_CHUNKS) {
            chunks_in_buffer++;
        }
    }

    // --- LED VOLUME FEEDBACK ---
    // Map the volume (RMS) to LED brightness (10 to 255)
    // Assume silence is around TRIGGER_THRESHOLD and loud talking hits ~600
    int brightness = map((long)rms, (long)TRIGGER_THRESHOLD, 600, 5, 255);
    brightness = constrain(brightness, 5, 255);
    FastLED.setBrightness(brightness);
    FastLED.show();

    if (currentState == LISTENING) {
        if (rms > TRIGGER_THRESHOLD) {
            Serial.printf("[!] Noise detected! RMS: %.2f. Firing trigger...\n", rms);
            
            if (!DEBUG_OFFLINE_MODE) {
                // Build the JSON trigger
                char json_payload[100];
                snprintf(json_payload, sizeof(json_payload), "{\"node\":\"%s\",\"amplitude\":%.1f}", node_id, rms);
                
                // Blast over UDP
                udp.beginPacket(server_ip, server_port);
                udp.print(json_payload);
                udp.endPacket();
            } else {
                Serial.println("[DEBUG] Simulated Wake-Word UDP blast.");
            }

            currentState = WAITING_ACK;
            silence_counter = 0;
            
            // Give the Java server a moment to reply
            delay(50); 
        }
    } 
    else if (currentState == WAITING_ACK) {
        if (DEBUG_OFFLINE_MODE) {
            Serial.println("[DEBUG] Auto-ACKing arbitration for offline mode...");
            currentState = STREAMING;
            stream_start_time = millis();
            chunks_in_buffer = 0; // Reset
            return;
        }

        int packetSize = udp.parsePacket();
        if (packetSize) {
            char ack_buffer[255];
            int len = udp.read(ack_buffer, 255);
            if (len > 0) ack_buffer[len] = '\0';
            
            if (String(ack_buffer) == "ACK_START_STREAM") {
                Serial.println("[+] Arbitration won! Streaming audio...");
                currentState = STREAMING;
                stream_start_time = millis();
                
                // Flush the ring buffer to the server so we don't miss the start of the word!
                if (!DEBUG_OFFLINE_MODE) {
                    Serial.printf("[+] Flushing %d chunks of pre-roll memory to server...\n", chunks_in_buffer);
                    int tail = (ring_head - chunks_in_buffer + PRE_ROLL_CHUNKS) % PRE_ROLL_CHUNKS;
                    for (int i = 0; i < chunks_in_buffer; i++) {
                        int idx = (tail + i) % PRE_ROLL_CHUNKS;
                        udp.beginPacket(server_ip, server_port);
                        udp.write(ring_buffer[idx], BUFFER_SIZE);
                        udp.endPacket();
                        delay(1); // 1ms delay to let the ESP32 Wi-Fi stack clear its TX buffer without dropping packets
                    }
                }
                chunks_in_buffer = 0; // Clear it for the next time
            }
        } else {
            // Time out quickly if no ACK received
            silence_counter++;
            if (silence_counter > 20) {
                Serial.println("[-] Arbitration lost/timeout. Back to listening.");
                flash_arbfail_led();
                currentState = LISTENING;
            }
        }
    } 
    else if (currentState == STREAMING) {
        if (!DEBUG_OFFLINE_MODE) {
            // Stream the raw audio buffer directly to Java
            udp.beginPacket(server_ip, server_port);
            udp.write(audio_buffer, bytes_read);
            udp.endPacket();
        } else {
            // In offline mode, output ambient noise if triggered mode has lasted longer than 10 seconds (10,000ms)
            if (millis() - stream_start_time > 10000) {
                if (millis() - last_debug_print > 250) { // Throttle print to 4x a second
                    Serial.printf("[DEBUG] Streaming >10s. Ambient RMS: %.2f\n", rms);
                    last_debug_print = millis();
                }
            }
        }

        // Track silence to know when the user stopped talking
        if (rms < SILENCE_THRESHOLD) {
            silence_counter++;
        } else {
            silence_counter = 0; // Reset if they are still talking
        }

        if (silence_counter >= MAX_SILENCE_CHUNKS) {
            Serial.println("[+] User stopped speaking. Sending STREAM_EOF.");
            
            if (!DEBUG_OFFLINE_MODE) {
                udp.beginPacket(server_ip, server_port);
                udp.print("STREAM_EOF");
                udp.endPacket();
            }
            
            // Play the visual feedback instead of audio
            flash_eof_led();
            
            // Reset state
            currentState = LISTENING;
            silence_counter = 0;
            chunks_in_buffer = 0; // Clear memory so we don't resend old EOF silence next time!

            // Reset filter states so residual streaming audio doesn't bleed into the next trigger
            hp_x1 = 0.0f;  hp_x2 = 0.0f;
            hp_y1 = 0.0f;  hp_y2 = 0.0f;
            hs_x1 = 0.0f;  hs_x2 = 0.0f;
            hs_y1 = 0.0f;  hs_y2 = 0.0f;

            // Briefly clear the I2S DMA buffer to avoid processing any hardware noise generated during the flash
            i2s_zero_dma_buffer(I2S_NUM_0);
            delay(500); // Debounce to prevent immediate re-trigger
        }
    }
}