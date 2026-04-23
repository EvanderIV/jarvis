package com.jarvis;

/**
 * Audio format validator and information class for Vosk compatibility
 * Vosk requires: 16000 Hz, 16-bit, Mono PCM audio
 */
public class AudioFormatValidator {
    
    // Vosk-compatible audio format specifications
    public static final int VOSK_SAMPLE_RATE = 16000;      // 16 kHz
    public static final int VOSK_BITS_PER_SAMPLE = 16;     // 16-bit
    public static final int VOSK_CHANNELS = 1;             // Mono
    public static final int VOSK_BYTES_PER_SAMPLE = VOSK_BITS_PER_SAMPLE / 8;  // 2 bytes
    
    /**
     * Validates if audio data is in Vosk-compatible format
     * 
     * @param audioBytes the raw PCM audio bytes
     * @param sampleRate the sample rate in Hz
     * @param bitsPerSample bits per sample (usually 16)
     * @param channels number of channels (1 for mono)
     * @return true if format matches Vosk requirements
     */
    public static boolean isVoskCompatible(byte[] audioBytes, int sampleRate, 
                                          int bitsPerSample, int channels) {
        boolean compatible = (sampleRate == VOSK_SAMPLE_RATE && 
                             bitsPerSample == VOSK_BITS_PER_SAMPLE && 
                             channels == VOSK_CHANNELS);
        
        if (!compatible) {
            System.err.println("[!] Audio format mismatch!");
            System.err.println("    Expected: " + VOSK_SAMPLE_RATE + "Hz, " + 
                             VOSK_BITS_PER_SAMPLE + "-bit, " + VOSK_CHANNELS + " channel(s)");
            System.err.println("    Got:      " + sampleRate + "Hz, " + 
                             bitsPerSample + "-bit, " + channels + " channel(s)");
        }
        
        return compatible;
    }
    
    /**
     * Calculates expected audio duration from byte count
     * 
     * @param audioBytes raw PCM audio bytes
     * @return duration in milliseconds
     */
    public static long calculateDurationMs(byte[] audioBytes) {
        int bytesPerFrame = VOSK_BYTES_PER_SAMPLE * VOSK_CHANNELS;
        int frames = audioBytes.length / bytesPerFrame;
        return (frames * 1000) / VOSK_SAMPLE_RATE;
    }
    
    /**
     * Gets a human-readable description of Vosk audio format requirements
     */
    public static String getFormatRequirements() {
        return String.format("Vosk Audio Format Requirements:\n" +
                           "  Sample Rate: %d Hz\n" +
                           "  Bits Per Sample: %d-bit\n" +
                           "  Channels: %d (Mono)\n" +
                           "  Format: PCM WAV",
                           VOSK_SAMPLE_RATE, VOSK_BITS_PER_SAMPLE, VOSK_CHANNELS);
    }
}
