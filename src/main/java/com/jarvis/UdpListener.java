package com.jarvis;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.vosk.Model;
import org.vosk.Recognizer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class UdpListener implements Runnable {

    // Max gap between audio packets before we assume STREAM_EOF was lost. The node
    // streams a chunk roughly every 50 ms (silence included) until it sends EOF.
    private final static int TIMEOUT_MS = 2000;
    private final DatagramSocket socket;
    private volatile boolean running = true;
    private Model voskModel;

    // Hold a persistent instance of the fulfiller so we don't reload the JSON on
    // every command
    private final CommandFulfiller fulfiller;

    // One parser for the lifetime of the server so its target history ("turn it up"
    // after "play music") carries over between commands. Only used on commandWorker.
    private final IntentParser parser = new IntentParser();

    // Parsing and fulfillment (LMS calls) run here so the listener can get straight
    // back to answering wake-word triggers. Single thread keeps commands in order.
    private final ExecutorService commandWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CommandWorker");
        t.setDaemon(true);
        return t;
    });

    private static String[] wakeTriggers = { "jarvis", "jervis", "darvish", "dervish", "dervis", "garbage",
            "jurgen", "drivers", "travis", "harvest", "journalist", "german", "germans",
            "georgia", "george", "jarred", "target", "judge", "your", "your this", "this",
            "jerk", "jerks", "service", "dravid", "just", "turkish", "job", "josh", "john", "drugs" };

    // UPDATED CONSTRUCTOR: Now accepts the CommandFulfiller from App.java
    public UdpListener(DatagramSocket socket, CommandFulfiller fulfiller) {
        this.socket = socket;
        this.fulfiller = fulfiller; // Save the injected fulfiller

        // Initialize the Vosk Model here so it only loads into memory once.
        try {
            String modelPath = "model";
            System.out.println("[*] Loading Vosk Model from '" + new java.io.File(modelPath).getAbsolutePath() + "'...");
            this.voskModel = new Model(modelPath);
            System.out.println("[+] Vosk Model loaded successfully.");
        } catch (Exception e) {
            System.err.println(
                    "[-] Failed to load Vosk model. Did you extract it to the 'model' directory in the project root?");
            e.printStackTrace();
        }

        switch (App.WAKE_WORD) {
            case "echo":
                wakeTriggers = new String[] { "echo", "ago", "go", "i go", "i can", "psycho" };
                break;
            case "alexa":
                wakeTriggers = new String[] { "alexa", "lecture", "a lecture", "lecturn", "a lecturn", "elixir" };
                break;
            case "mycroft":
                wakeTriggers = new String[] { "mycroft", "microsoft", "my craft", "my draft", "my grabbed", "my dropped", "my shrugged", "my grab", "mike robbed" };
                break;
            case "ineffa":
                // "Ineffa" isn't in the Vosk vocabulary, so it comes out as similar-sounding
                // words. Variants below were collected by running synthesized speech of each
                // pronunciation through the model; longer phrases first.
                wakeTriggers = new String[] {
                        "ineffa",
                        // ee-neff-uh (preferred)
                        "he never", "enough for", "enough off", "enough on", "enough i", "innova",
                        "in a fact", "in a far", "ina far", "ina ah", "in a fuck",
                        // eye-neff-uh
                        "i have never", "i have ever", "i an effort", "i never",
                        // ih-neff-uh
                        "i each never", "each never", "i h never", "i'd never", "i've never",
                        "and never", "in never", "in ever", "in africa", "in alpha", "in a for", "in for" };
                break;
            case "cortana":
                wakeTriggers = new String[] { "cortana", "cortona", "core kana", "court on a", "court on", "quarter", "we're gonna", "corker" };
                break;
            default: // "jarvis"
                // wakeTriggers = new String[] { "jarvis", "jervis", "darvish", "dervish",
                // "dervis", "garbage",
                // "jurgen", "drivers", "travis", "harvest", "journalist", "german", "germans",
                // "georgia", "jarred", "target", "judge", "your this", "this", "jerks",
                // "service" };
                break;
        }
    }

    @Override
    public void run() {
        // Buffer to hold incoming packet data (1024 bytes matches the emulator)
        byte[] buffer = new byte[1024];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Blocks here until a wake-word trigger packet is received
                socket.receive(packet);

                String receivedJson = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                // Basic check to ensure this is a JSON trigger and not rogue data
                if (receivedJson.contains("\"node\"") && receivedJson.contains("\"amplitude\"")) {
                    String senderIp = packet.getAddress().getHostAddress();
                    int senderPort = packet.getPort(); // The port we must reply to

                    System.out.println(
                            "\n[*] Wake-word trigger from [" + senderIp + ":" + senderPort + "]: " + receivedJson);

                    // 1. Send the ACK to win arbitration
                    System.out.println("[*] Emulating Arbitration win. Sending ACK_START_STREAM...");
                    byte[] ackBytes = "ACK_START_STREAM".getBytes(StandardCharsets.UTF_8);
                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length, packet.getAddress(),
                            senderPort);
                    socket.send(ackPacket);

                    // 2. Catch the incoming audio stream
                    System.out.println("[*] Listening for raw PCM audio stream...");
                    ByteArrayOutputStream audioStreamBuffer = new ByteArrayOutputStream();

                    // Set a 7-second timeout for audio reception
                    // (commands are typically much shorter than this)
                    int previousTimeout = socket.getSoTimeout();
                    socket.setSoTimeout(TIMEOUT_MS);

                    try {
                        while (running) {
                            DatagramPacket audioPacket = new DatagramPacket(buffer, buffer.length);
                            socket.receive(audioPacket);

                            // Ignore anything not from the node we're streaming from (another
                            // node's trigger or audio would otherwise be spliced into this PCM).
                            // That node never gets an ACK, so it loses arbitration as intended.
                            if (audioPacket.getPort() != senderPort
                                    || !audioPacket.getAddress().equals(packet.getAddress())) {
                                continue;
                            }

                            // Check if this packet is the EOF signal instead of audio data
                            String possibleEof = new String(audioPacket.getData(), 0, audioPacket.getLength(),
                                    StandardCharsets.UTF_8);
                            if (possibleEof.equals("STREAM_EOF")) {
                                //System.out.println("[+] Received STREAM_EOF.");
                                break; // Break out of the audio loop, go back to waiting for triggers
                            }

                            // Otherwise, it's raw audio bytes. Write them to our in-memory buffer.
                            audioStreamBuffer.write(audioPacket.getData(), 0, audioPacket.getLength());
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("[*] Audio stream timeout (" + TIMEOUT_MS / 1000
                                + " seconds elapsed). Stopping audio capture.");
                    } finally {
                        // Restore the original socket timeout
                        socket.setSoTimeout(previousTimeout);
                    }

                    byte[] completeAudioPayload = audioStreamBuffer.toByteArray();
                    //System.out.println("[+] Stream complete! Captured " + completeAudioPayload.length + " bytes of raw audio.");

                    if (App.DUMP_PCM && completeAudioPayload.length > 0) {
                        String dumpPath = "/home/evanm/Documents/jarvis-pcm/"
                                + System.currentTimeMillis() + "_" + senderIp.replace(".", "-") + ".pcm";
                        try {
                            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("/home/evanm/Documents/jarvis-pcm"));
                            java.nio.file.Files.write(java.nio.file.Paths.get(dumpPath), completeAudioPayload);
                            System.out.println("[+] PCM dumped to: " + dumpPath);
                        } catch (Exception e) {
                            System.err.println("[-] Failed to dump PCM: " + e.getMessage());
                        }
                    }

                    // Validate audio format
                    AudioFormatValidator.isVoskCompatible(completeAudioPayload,
                            AudioFormatValidator.VOSK_SAMPLE_RATE,
                            AudioFormatValidator.VOSK_BITS_PER_SAMPLE,
                            AudioFormatValidator.VOSK_CHANNELS);
                    //long durationMs = AudioFormatValidator.calculateDurationMs(completeAudioPayload);
                    //System.out.println("[*] Audio duration: " + durationMs + " ms");

                    // Pass the captured audio to Vosk
                    if (voskModel != null && completeAudioPayload.length > 0) {
                        //System.out.println("[*] Normalizing and transcribing audio...");
                        byte[] audioToTranscribe = normalize(completeAudioPayload, 3000.0f);
                        long recognizeStart = System.nanoTime();
                        try (Recognizer recognizer = new Recognizer(voskModel, 16000)) {
                            recognizer.acceptWaveForm(audioToTranscribe, audioToTranscribe.length);

                            // Extract the final JSON result
                            String resultJson = recognizer.getFinalResult();

                            Gson gson = new Gson();
                            JsonObject jsonObject = gson.fromJson(resultJson, JsonObject.class);
                            String transcribedText = jsonObject.has("text") ? jsonObject.get("text").getAsString() : "";

                            String jarvisRegex = "(?i)\\b(";
                            for (int i = 0; i < wakeTriggers.length - 1; i++) {
                                jarvisRegex += wakeTriggers[i] + " |";
                            }
                            jarvisRegex += wakeTriggers[wakeTriggers.length - 1] + " )\\b";

                            String[] prefixCommands = { "good morning", "good night", "goodnight" };

                            String overrideEndCasesForCommands = "";
                            for (int i = 0; i < wakeTriggers.length; i++) {
                                for (String prefCmd : prefixCommands) {
                                    overrideEndCasesForCommands += prefCmd + " " + wakeTriggers[i] + "|";
                                }
                            }
                            overrideEndCasesForCommands = overrideEndCasesForCommands.substring(0,
                                    overrideEndCasesForCommands.length() - 1); // Remove last '|'

                            String alternateWakeWordsRegex = "(?i)\\b(wake up daddy's home|wake up daddy home|we got daddy's home|we got daddy home|we've got daddy's home|we've got daddy home|coachella music|"
                                    + overrideEndCasesForCommands + ")\\b";

                            String cleanedText = transcribedText.replaceAll(jarvisRegex, "").trim();

                            long audioMs = completeAudioPayload.length / 32; // 16 kHz * 2 bytes = 32 bytes/ms
                            long recognizeMs = (System.nanoTime() - recognizeStart) / 1_000_000;
                            System.out.println("[+] Vosk Transcription (" + audioMs + " ms audio, "
                                    + recognizeMs + " ms to recognize): " + transcribedText);

                            // Detect jarvis wake word to execute command and parse intent)
                            if (transcribedText.isEmpty()
                                    || transcribedText.toLowerCase().matches(".*" + jarvisRegex + ".*")
                                    || transcribedText.toLowerCase().matches(".*" + alternateWakeWordsRegex + ".*")) {
                                commandWorker.submit(() -> handleCommand(cleanedText));
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (running) {
                    System.err.println("Error in UDP listener: " + e.getMessage());
                }
            }
        }
    }

    /** Runs a text command as if it had been spoken (admin console "say"). */
    public void submitText(String text) {
        commandWorker.submit(() -> handleCommand(text));
    }

    private void handleCommand(String cleanedText) {
        try {
            ParsedCommand command = parser.parse(cleanedText);
            System.out.println("[+] Parsed Command: " + command);

            // Execute the command using the persistently loaded fulfiller
            CommandFulfiller.CommandResult result = fulfiller.fulfill(command);
            System.out.println(result);
        } catch (MissingContextualTargetException e) {
            System.err.println("[-] Parsing Error: " + e.getMessage());
        } catch (CommandUnfulfillableException e) {
            System.err.println("[-] Error in command worker: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[-] Error in command worker: " + e);
        }
    }

    // Scales 16-bit little-endian PCM to a target RMS level, capped at 4x gain.
    // Matches the AGC target on the ESP32 so both sides agree on a nominal level.
    private static byte[] normalize(byte[] pcm, float targetRms) {
        int numSamples = pcm.length / 2;
        if (numSamples == 0) return pcm;

        ByteBuffer readBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        for (int i = 0; i < numSamples; i++) {
            short s = readBuf.getShort(i * 2);
            sum += (double) s * s;
        }
        float rms = (float) Math.sqrt(sum / numSamples);
        if (rms < 10f) return pcm; // silence — skip

        float gain = Math.min(targetRms / rms, 4.0f);
        //System.out.printf("[*] Audio normalization: RMS=%.1f, gain=%.2fx%n", rms, gain);

        byte[] out = new byte[pcm.length];
        ByteBuffer writeBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        readBuf.rewind();
        for (int i = 0; i < numSamples; i++) {
            short s = readBuf.getShort();
            int amplified = Math.max(-32768, Math.min(32767, (int) (s * gain)));
            writeBuf.putShort((short) amplified);
        }
        return out;
    }

    public void stop() {
        this.running = false;
        commandWorker.shutdown();
    }
}