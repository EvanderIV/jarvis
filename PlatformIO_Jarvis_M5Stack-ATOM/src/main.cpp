#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <driver/i2s.h>
#include <math.h>
#include <FastLED.h>

// --- DEBUG / OFFLINE MODE ---
// Set to true to skip Wi-Fi and UDP. It will emulate winning arbitration locally
// and skip network streaming. Great for testing the mic sensitivity, LED, and speaker!
const bool DEBUG_OFFLINE_MODE = false;

// --- NETWORK CONFIG ---
const char* ssid = "ArchMinichs";
const char* password = "Sh33pAndL!nuxBoys";
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
const float MIC_GAIN = 1.5;

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
const double TRIGGER_THRESHOLD = 310.0;
const double SILENCE_THRESHOLD = 250.0;
const int MAX_SILENCE_CHUNKS = 30; // Stop streaming after ~1.5 seconds of silence

enum State { LISTENING, WAITING_ACK, STREAMING };
State currentState = LISTENING;
int silence_counter = 0;
unsigned long stream_start_time = 0;
unsigned long last_debug_print = 0;

void setup_i2s() {
    Serial.println("[*] Initializing ATOM Echo I2S Microphone...");
    
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM), // ATOM uses PDM mic
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 8,   // INCREASED: Gives the ESP32 more hardware headroom
        .dma_buf_len = 1024,  // INCREASED: Prevents audio dropping while flushing the pre-roll
        .use_apll = false
    };

    i2s_pin_config_t pin_config = {
        .bck_io_num = CONFIG_I2S_BCK_PIN,
        .ws_io_num = CONFIG_I2S_LRCK_PIN,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num = CONFIG_I2S_DATA_PIN
    };

    i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL);
    i2s_set_pin(I2S_NUM_0, &pin_config);
    i2s_set_clk(I2S_NUM_0, SAMPLE_RATE, I2S_BITS_PER_SAMPLE_16BIT, I2S_CHANNEL_MONO);
}

double get_rms(uint8_t* buffer, size_t bytes_read) {
    int16_t* samples = (int16_t*)buffer;
    int num_samples = bytes_read / 2;
    double sum = 0;
    for (int i = 0; i < num_samples; i++) {
        sum += samples[i] * samples[i];
    }
    return sqrt(sum / num_samples);
}

void flash_eof_led() {
    Serial.println("[*] Flashing EOF Visual Feedback...");
    
    // Quick double flash of bright orange
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
    Serial.println("[*] Flashing EOF Visual Feedback...");
    
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
    delay(1000);

    // --- THERMAL MANAGEMENT ---
    // Downclock CPU from 240MHz to 80MHz to drastically reduce heat and power draw
    setCpuFrequencyMhz(80); 
    Serial.printf("[*] CPU Frequency set to: %d MHz\n", getCpuFrequencyMhz());

    // 0. Init LED
    FastLED.addLeds<WS2812, LED_PIN, GRB>(leds, NUM_LEDS);
    leds[0] = CRGB(0, 150, 255); // Cool cyber blue
    FastLED.setBrightness(5);   // Default dim glow
    FastLED.show();

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
    setup_i2s();
    
    Serial.println("[+] Satellite is LIVE. Listening for noise...");
}

void apply_gain(uint8_t* buffer, size_t bytes_read) {
  int16_t* samples = (int16_t*)buffer;
  int num_samples = bytes_read / 2;

  for (int i = 0; i < num_samples; i++) {
    int32_t amplified = (int32_t)(samples[i] * MIC_GAIN);

    if (amplified > 32767) amplified = 32767;
    if (amplified < -32768) amplified = -32768;

    samples[i] = (int16_t)amplified;
  }
}

void loop() {
    size_t bytes_read;
    i2s_read(I2S_NUM_0, &audio_buffer, BUFFER_SIZE, &bytes_read, portMAX_DELAY);
    
    if (bytes_read == 0) return;

    apply_gain(audio_buffer, bytes_read);

    double rms = get_rms(audio_buffer, bytes_read) / MIC_GAIN;

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
            
            // Briefly clear the I2S DMA buffer to avoid processing any hardware noise generated during the flash
            i2s_zero_dma_buffer(I2S_NUM_0); 
            delay(500); // Debounce to prevent immediate re-trigger
        }
    }
}