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
     * Note: Java's AudioSystem only natively supports WAV, AIFF, and AU formats.
     * MP3 and other compressed formats are not supported. Files in unsupported
     * formats will be skipped from analysis, but playback will continue normally.
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
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            String fileExt = getFileExtension(filePath);
            System.out.println("[!] AudioAnalyzer: Skipping analysis - unsupported format '." + fileExt + "'");
            System.out.println("    Supported formats: WAV, AIFF, AU");
            System.out.println("    File will play normally without fadeout detection: " + filePath);
            // Cache the null result to avoid repeated analysis failures
            fadeoutCache.put(filePath, null);
            return null;
        } catch (Exception e) {
            System.err.println("[-] AudioAnalyzer: Error analyzing '" + filePath + "': " + e.getMessage());
            // Cache the null result to avoid repeated analysis failures
            fadeoutCache.put(filePath, null);
            return null;
        }
    }
    
    /**
     * Extracts file extension from path.
     */
    private static String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filePath.length() - 1) {
            return filePath.substring(lastDot + 1).toLowerCase();
        }
        return "unknown";
    }
    
    /**
     * Analyzes audio with automatic conversion for unsupported formats.
     * If the file is in an unsupported format (e.g., MP3), it will:
     * 1. Convert to temporary WAV file
     * 2. Analyze the WAV
     * 3. Cache the result under the original file path
     * 4. Delete the temporary WAV
     * 
     * This is meant to be called asynchronously (in a background thread)
     * after playback has already started.
     * 
     * @param filePath Absolute path to the audio file
     * @return FadeoutInfo containing the fadeout timestamp, or null if analysis fails
     */
    public static FadeoutInfo analyzeAudioWithConversion(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        
        // Check cache first
        if (fadeoutCache.containsKey(filePath)) {
            FadeoutInfo cached = fadeoutCache.get(filePath);
            if (cached != null && App.DEBUG_MODE) {
                System.out.println("[DEBUG] AudioAnalyzer: Using cached fadeout info for " + filePath);
            }
            return cached;
        }
        
        try {
            // Try direct analysis first (for WAV, AIFF, AU files)
            FadeoutInfo result = performAnalysis(filePath);
            fadeoutCache.put(filePath, result);
            
            if (result != null && App.DEBUG_MODE) {
                System.out.println("[DEBUG] AudioAnalyzer: Analysis complete (direct) - " + result);
            }
            return result;
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            // File is in an unsupported format - try converting to WAV
            String fileExt = getFileExtension(filePath);
            System.out.println("[*] AudioAnalyzer: Converting ." + fileExt + " to WAV for analysis...");
            
            String tempWavPath = null;
            try {
                tempWavPath = convertToWav(filePath);
                if (tempWavPath == null) {
                    // Conversion failed
                    fadeoutCache.put(filePath, null);
                    return null;
                }
                
                // Analyze the temporary WAV file
                FadeoutInfo result = performAnalysis(tempWavPath);
                
                // Cache using the original file path so the result is reusable
                fadeoutCache.put(filePath, result);
                
                if (result != null && App.DEBUG_MODE) {
                    System.out.println("[DEBUG] AudioAnalyzer: Analysis complete (converted) - " + result);
                }
                
                return result;
            } catch (Exception analysisError) {
                System.err.println("[-] AudioAnalyzer: Error analyzing converted file: " + analysisError.getMessage());
                fadeoutCache.put(filePath, null);
                return null;
            } finally {
                // Always clean up the temporary WAV file (if conversion succeeded)
                if (tempWavPath != null) {
                    File tempFile = new File(tempWavPath);
                    if (tempFile.exists()) {
                        if (tempFile.delete()) {
                            if (App.DEBUG_MODE) {
                                System.out.println("[DEBUG] AudioAnalyzer: Deleted temporary WAV file");
                            }
                        } else {
                            System.err.println("[-] AudioAnalyzer: Could not delete temporary WAV file: " + tempWavPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[-] AudioAnalyzer: Error analyzing '" + filePath + "': " + e.getMessage());
            fadeoutCache.put(filePath, null);
            return null;
        }
    }
    
    /**
     * Converts an audio file to WAV format.
     * Returns the path to the temporary WAV file, or null if conversion fails.
     */
    private static String convertToWav(String sourcePath) {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            System.err.println("[-] AudioAnalyzer: Source file not found: " + sourcePath);
            return null;
        }
        
        String tempPath = sourcePath + ".analysis.wav";
        File tempWavFile = new File(tempPath);
        
        AudioInputStream sourceStream = null;
        try {
            // Try to read the source audio
            sourceStream = AudioSystem.getAudioInputStream(sourceFile);
            AudioFormat sourceFormat = sourceStream.getFormat();
            
            // Convert to standard WAV format (PCM, 44.1kHz, 16-bit, mono/stereo)
            AudioFormat wavFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44100,                           // Sample rate
                16,                              // Sample size in bits
                sourceFormat.getChannels(),      // Keep original channel count
                sourceFormat.getChannels() * 2,  // Frame size
                44100,                           // Frame rate
                false                            // Little endian
            );
            
            // Convert and write to temporary WAV
            try (AudioInputStream convertedStream = AudioSystem.getAudioInputStream(wavFormat, sourceStream)) {
                int bytesWritten = AudioSystem.write(convertedStream, javax.sound.sampled.AudioFileFormat.Type.WAVE, tempWavFile);
                
                if (bytesWritten > 0) {
                    if (App.DEBUG_MODE) {
                        System.out.println("[DEBUG] AudioAnalyzer: Converted to WAV (" + bytesWritten + " bytes)");
                    }
                    return tempPath;
                } else {
                    System.err.println("[-] AudioAnalyzer: No bytes written during conversion");
                    if (tempWavFile.exists()) {
                        tempWavFile.delete();
                    }
                    return null;
                }
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            System.err.println("[-] AudioAnalyzer: Cannot convert - format not supported by system: " + e.getMessage());
            if (tempWavFile.exists()) {
                tempWavFile.delete();
            }
            return null;
        } catch (Exception e) {
            System.err.println("[-] AudioAnalyzer: Conversion to WAV failed: " + e.getMessage());
            if (tempWavFile.exists()) {
                tempWavFile.delete();
            }
            return null;
        } finally {
            // Close the source stream if it was opened
            if (sourceStream != null) {
                try {
                    sourceStream.close();
                } catch (Exception e) {
                    System.err.println("[-] AudioAnalyzer: Error closing source stream: " + e.getMessage());
                }
            }
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
