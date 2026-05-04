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
const char* ssid = "Evan Moto Power";
const char* password = "PromisedLAN";
const char* server_ip = "eminich.com"; // <-- CHANGE THIS to your Java server's IP!
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

// --- LED CONFIG ---
#define LED_PIN 27
#define NUM_LEDS 1
CRGB leds[NUM_LEDS];

// --- VAD (Voice Activity) CONFIG ---
// Adjust these if the mic triggers too easily or not easily enough!
const double TRIGGER_THRESHOLD = 300.0; 
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
        .dma_buf_count = 4,
        .dma_buf_len = 512,
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
    for (int i = 0; i < 2; i++) {
        leds[0] = CRGB::Orange;
        FastLED.setBrightness(255);
        FastLED.show();
        delay(150);
        
        FastLED.setBrightness(0);
        FastLED.show();
        delay(100);
    }
    
    // Restore default dim blue glow
    leds[0] = CRGB(0, 150, 255);
    FastLED.setBrightness(10);
    FastLED.show();
}

void setup() {
    Serial.begin(115200);
    delay(1000);

    // 0. Init LED
    FastLED.addLeds<WS2812, LED_PIN, GRB>(leds, NUM_LEDS);
    leds[0] = CRGB(0, 150, 255); // Cool cyber blue
    FastLED.setBrightness(10);   // Default dim glow
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

        // 3. Init UDP
        udp.begin(server_port);
    } else {
        Serial.println("\n[!] RUNNING IN DEBUG_OFFLINE_MODE. Wi-Fi and UDP skipped.");
    }

    // 2. Init Microphone
    setup_i2s();
    
    Serial.println("[+] Satellite is LIVE. Listening for noise...");
}

void loop() {
    size_t bytes_read;
    i2s_read(I2S_NUM_0, &audio_buffer, BUFFER_SIZE, &bytes_read, portMAX_DELAY);
    
    if (bytes_read == 0) return;

    double rms = get_rms(audio_buffer, bytes_read);

    // --- LED VOLUME FEEDBACK ---
    // Map the volume (RMS) to LED brightness (10 to 255)
    // Assume silence is around SILENCE_THRESHOLD and loud talking hits ~1200
    int brightness = map((long)rms, (long)SILENCE_THRESHOLD, 1200, 10, 255);
    brightness = constrain(brightness, 10, 255);
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
            }
        } else {
            // Time out quickly if no ACK received
            silence_counter++;
            if (silence_counter > 20) {
                Serial.println("[-] Arbitration lost/timeout. Back to listening.");
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
            delay(500); // Debounce to prevent immediate re-trigger
        }
    }
}