package com.jarvis;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes audio files to detect fadeouts and silence.
 * Returns the timestamp at which a song should be automatically skipped
 * due to the audio level dropping below a threshold.
 */
public class AudioAnalyzer {
    
    // Configuration constants
    private static final float LOW_VOLUME_RATIO = 0.25f;      // Skip if volume drops below 25% of average
    private static final float ANALYSIS_WINDOW_PERCENT = 0.25f; // Only analyze the last 25% of the song
    private static final int SAMPLE_BUFFER_SIZE = 4096;        // Number of bytes to read at a time
    
    // Cached fadeout info to avoid re-analyzing the same file
    private static final java.util.Map<String, FadeoutInfo> fadeoutCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Analyzes an audio file and returns the timestamp where fadeout is detected.
     * Results are cached to avoid re-analyzing the same file.
     * 
     * @param filePath Absolute path to the audio file
     * @return FadeoutInfo containing the fadeout timestamp, or null if no fadeout detected or file is unreadable
     */
    public static FadeoutInfo analyzeFadeout(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        
        // Check cache first
        if (fadeoutCache.containsKey(filePath)) {
            return fadeoutCache.get(filePath);
        }
        
        try {
            FadeoutInfo result = performAnalysis(filePath);
            fadeoutCache.put(filePath, result);
            return result;
        } catch (Exception e) {
            System.err.println("[-] AudioAnalyzer: Error analyzing '" + filePath + "': " + e.getMessage());
            // Cache the null result to avoid repeated analysis failures
            fadeoutCache.put(filePath, null);
            return null;
        }
    }
    
    /**
     * Performs the actual audio analysis.
     */
    private static FadeoutInfo performAnalysis(String filePath) throws Exception {
        File audioFile = new File(filePath);
        if (!audioFile.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        
        // Get audio file format information
        AudioFileFormat audioFileFormat = AudioSystem.getAudioFileFormat(audioFile);
        AudioFormat audioFormat = audioFileFormat.getFormat();
        int frameLength = audioFileFormat.getFrameLength();
        
        if (frameLength <= 0) {
            throw new IllegalArgumentException("Unable to determine audio file duration");
        }
        
        // Calculate duration and analysis window
        float sampleRate = audioFormat.getSampleRate();
        float durationSeconds = frameLength / sampleRate;
        float analysisStartSeconds = durationSeconds * (1 - ANALYSIS_WINDOW_PERCENT);
        
        if (App.DEBUG_MODE) {
            System.out.println("[DEBUG] AudioAnalyzer: File: " + filePath);
            System.out.println("[DEBUG]   Duration: " + String.format("%.1f", durationSeconds) + "s");
            System.out.println("[DEBUG]   Sample rate: " + String.format("%.0f", sampleRate) + " Hz");
            System.out.println("[DEBUG]   Analysis window: " + String.format("%.1f", analysisStartSeconds) + "s - " + 
                             String.format("%.1f", durationSeconds) + "s");
        }
        
        // Read audio data and extract volume levels
        List<Float> volumeLevels = extractVolumeLevels(audioFile, audioFormat, analysisStartSeconds, sampleRate);
        
        if (volumeLevels.isEmpty()) {
            if (App.DEBUG_MODE) {
                System.out.println("[DEBUG] AudioAnalyzer: No volume data extracted");
            }
            return null;
        }
        
        // Calculate average volume
        float averageVolume = 0;
        for (float level : volumeLevels) {
            averageVolume += level;
        }
        averageVolume /= volumeLevels.size();
        
        // Find where volume drops below 25% of average
        float volumeThreshold = averageVolume * LOW_VOLUME_RATIO;
        int fadeoutFrameIndex = findFadeoutPoint(volumeLevels, volumeThreshold, analysisStartSeconds, sampleRate);
        
        if (fadeoutFrameIndex < 0) {
            if (App.DEBUG_MODE) {
                System.out.println("[DEBUG] AudioAnalyzer: No fadeout detected (avg=" + 
                                 String.format("%.2f", averageVolume) + ", threshold=" + 
                                 String.format("%.2f", volumeThreshold) + ")");
            }
            return null;
        }
        
        float fadeoutTimestamp = (fadeoutFrameIndex / sampleRate);
        
        if (App.DEBUG_MODE) {
            System.out.println("[DEBUG] AudioAnalyzer: Fadeout detected at " + 
                             String.format("%.1f", fadeoutTimestamp) + "s (avg=" + 
                             String.format("%.2f", averageVolume) + ", threshold=" + 
                             String.format("%.2f", volumeThreshold) + ")");
        }
        
        return new FadeoutInfo(filePath, fadeoutTimestamp, durationSeconds, averageVolume);
    }
    
    /**
     * Extracts volume levels from the audio file's analysis window.
     */
    private static List<Float> extractVolumeLevels(File audioFile, AudioFormat audioFormat, 
                                                   float analysisStartSeconds, float sampleRate) throws Exception {
        List<Float> volumeLevels = new ArrayList<>();
        
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile)) {
            // Skip to analysis window
            long skipFrames = (long) (analysisStartSeconds * sampleRate);
            audioStream.skip(skipFrames * audioFormat.getFrameSize());
            
            byte[] buffer = new byte[SAMPLE_BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = audioStream.read(buffer, 0, SAMPLE_BUFFER_SIZE)) != -1) {
                // Calculate RMS (Root Mean Square) for this chunk
                float rms = calculateRMS(buffer, bytesRead, audioFormat);
                volumeLevels.add(rms);
            }
        }
        
        return volumeLevels;
    }
    
    /**
     * Calculates RMS (volume level) from raw audio bytes.
     */
    private static float calculateRMS(byte[] buffer, int bytesRead, AudioFormat audioFormat) {
        int sampleSize = audioFormat.getSampleSizeInBits() / 8;
        int numSamples = bytesRead / sampleSize;
        
        if (numSamples == 0) return 0;
        
        double sumSquares = 0;
        boolean isSigned = audioFormat.getEncoding().toString().startsWith("PCM_SIGNED");
        boolean isBigEndian = audioFormat.isBigEndian();
        
        for (int i = 0; i < numSamples; i++) {
            int sampleValue = extractSample(buffer, i, sampleSize, isSigned, isBigEndian);
            sumSquares += sampleValue * sampleValue;
        }
        
        double meanSquare = sumSquares / numSamples;
        return (float) Math.sqrt(meanSquare);
    }
    
    /**
     * Extracts a single audio sample from the buffer.
     */
    private static int extractSample(byte[] buffer, int sampleIndex, int sampleSizeBytes, 
                                     boolean isSigned, boolean isBigEndian) {
        int byteOffset = sampleIndex * sampleSizeBytes;
        int sample = 0;
        
        if (sampleSizeBytes == 1) {
            sample = buffer[byteOffset] & 0xFF;
            if (isSigned && (sample & 0x80) != 0) {
                sample -= 256;
            }
        } else if (sampleSizeBytes == 2) {
            if (isBigEndian) {
                sample = ((buffer[byteOffset] & 0xFF) << 8) | (buffer[byteOffset + 1] & 0xFF);
            } else {
                sample = ((buffer[byteOffset + 1] & 0xFF) << 8) | (buffer[byteOffset] & 0xFF);
            }
            if (isSigned && (sample & 0x8000) != 0) {
                sample -= 65536;
            }
        }
        
        return sample;
    }
    
    /**
     * Finds the first point where volume drops below the threshold.
     */
    private static int findFadeoutPoint(List<Float> volumeLevels, float volumeThreshold, 
                                        float analysisStartSeconds, float sampleRate) {
        // Use a rolling average to smooth out noise
        int windowSize = Math.max(1, (int) (sampleRate / 2000)); // ~500ms window
        
        for (int i = 0; i < volumeLevels.size(); i++) {
            float rollingAverage = 0;
            int count = 0;
            
            // Calculate average across the window
            for (int j = Math.max(0, i - windowSize); j <= i && j < volumeLevels.size(); j++) {
                rollingAverage += volumeLevels.get(j);
                count++;
            }
            rollingAverage /= count;
            
            // If this point is below threshold, return its frame index
            if (rollingAverage < volumeThreshold) {
                return (int) (analysisStartSeconds * sampleRate) + i;
            }
        }
        
        return -1; // No fadeout detected
    }
    
    /**
     * Clears the fadeout cache (useful for testing or when files are updated).
     */
    public static void clearCache() {
        fadeoutCache.clear();
    }
    
    /**
     * Container for fadeout analysis results.
     */
    public static class FadeoutInfo {
        public final String filePath;
        public final float fadeoutTimestamp;    // Seconds from start where fadeout begins
        public final float totalDuration;       // Total duration of the song
        public final float averageVolume;       // Average volume during analysis window
        
        public FadeoutInfo(String filePath, float fadeoutTimestamp, float totalDuration, float averageVolume) {
            this.filePath = filePath;
            this.fadeoutTimestamp = fadeoutTimestamp;
            this.totalDuration = totalDuration;
            this.averageVolume = averageVolume;
        }
        
        /**
         * Gets how many seconds remain from the fadeout point to the end of the song.
         */
        public float getSecondsUntilEnd() {
            return totalDuration - fadeoutTimestamp;
        }
        
        @Override
        public String toString() {
            return "FadeoutInfo{" +
                    "filePath='" + filePath + '\'' +
                    ", fadeoutTimestamp=" + String.format("%.1f", fadeoutTimestamp) + "s" +
                    ", totalDuration=" + String.format("%.1f", totalDuration) + "s" +
                    ", averageVolume=" + String.format("%.2f", averageVolume) +
                    '}';
        }
    }
}
