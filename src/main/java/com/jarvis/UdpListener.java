package com.jarvis;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.vosk.Model;
import org.vosk.Recognizer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class UdpListener implements Runnable {

    private final static int TIMEOUT_SECONDS = 5000;
    private final DatagramSocket socket;
    private volatile boolean running = true;
    private Model voskModel;

    // Hold a persistent instance of the fulfiller so we don't reload the JSON on
    // every command
    private final CommandFulfiller fulfiller;

    private static String[] wakeTriggers = { "jarvis", "jervis", "darvish", "dervish", "dervis", "garbage",
            "jurgen", "drivers", "travis", "harvest", "journalist", "german", "germans",
            "georgia", "jarred", "target", "judge", "your this", "this", "jerks", "service" };

    // UPDATED CONSTRUCTOR: Now accepts the CommandFulfiller from App.java
    public UdpListener(DatagramSocket socket, CommandFulfiller fulfiller) {
        this.socket = socket;
        this.fulfiller = fulfiller; // Save the injected fulfiller

        // Initialize the Vosk Model here so it only loads into memory once.
        try {
            System.out.println("[*] Loading Vosk Model...");
            // Ensure you have a folder named "model" in your project root
            this.voskModel = new Model("model");
            System.out.println("[+] Vosk Model loaded successfully.");
        } catch (Exception e) {
            System.err.println(
                    "[-] Failed to load Vosk model. Did you extract it to the 'model' directory in the project root?");
            e.printStackTrace();
        }

        switch (App.WAKE_WORD) {
            case "echo":
                wakeTriggers = new String[] { "echo", "ago", "go", "i go" };
                break;
            case "alexa":
                wakeTriggers = new String[] { "echo", "ago", "go", "i go" };
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
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);

                    try {
                        while (running) {
                            DatagramPacket audioPacket = new DatagramPacket(buffer, buffer.length);
                            socket.receive(audioPacket);

                            // Check if this packet is the EOF signal instead of audio data
                            String possibleEof = new String(audioPacket.getData(), 0, audioPacket.getLength(),
                                    StandardCharsets.UTF_8);
                            if (possibleEof.equals("STREAM_EOF")) {
                                System.out.println("[+] Received STREAM_EOF.");
                                break; // Break out of the audio loop, go back to waiting for triggers
                            }

                            // Otherwise, it's raw audio bytes. Write them to our in-memory buffer.
                            audioStreamBuffer.write(audioPacket.getData(), 0, audioPacket.getLength());
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("[*] Audio stream timeout (" + TIMEOUT_SECONDS
                                + " seconds elapsed). Stopping audio capture.");
                    } finally {
                        // Restore the original socket timeout
                        socket.setSoTimeout(previousTimeout);
                    }

                    byte[] completeAudioPayload = audioStreamBuffer.toByteArray();
                    System.out.println(
                            "[+] Stream complete! Captured " + completeAudioPayload.length + " bytes of raw audio.");

                    // Validate audio format
                    AudioFormatValidator.isVoskCompatible(completeAudioPayload,
                            AudioFormatValidator.VOSK_SAMPLE_RATE,
                            AudioFormatValidator.VOSK_BITS_PER_SAMPLE,
                            AudioFormatValidator.VOSK_CHANNELS);
                    long durationMs = AudioFormatValidator.calculateDurationMs(completeAudioPayload);
                    System.out.println("[*] Audio duration: " + durationMs + " ms");

                    // Pass the captured audio to Vosk
                    if (voskModel != null && completeAudioPayload.length > 0) {
                        System.out.println("[*] Transcribing audio...");
                        // Create a recognizer for this specific audio stream (16000 Hz is standard for
                        // ESP32/Emulators)
                        try (Recognizer recognizer = new Recognizer(voskModel, 16000)) {
                            // Feed the entire byte array to the recognizer
                            recognizer.acceptWaveForm(completeAudioPayload, completeAudioPayload.length);

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

                            String alternateWakeWordsRegex = "(?i)\\b(wake up daddy's home|wake up daddy home|we got daddy's home|we got daddy home|we've got daddy's home|we've got daddy home|"
                                    + overrideEndCasesForCommands + ")\\b";

                            String cleanedText = transcribedText.replaceAll(jarvisRegex, "").trim();

                            System.out.println("[+] Vosk Transcription: " + transcribedText);

                            // Detect jarvis wake word to execute command and parse intent)
                            if (transcribedText.toLowerCase().matches(".*" + jarvisRegex + ".*")
                                    || transcribedText.toLowerCase().matches(".*" + alternateWakeWordsRegex + ".*")) {
                                IntentParser parser = new IntentParser();
                                ParsedCommand command = parser.parse(cleanedText);
                                System.out.println("[+] Parsed Command: " + command);

                                // Execute the command using the persistently loaded fulfiller
                                CommandFulfiller.CommandResult result = fulfiller.fulfill(command);
                                System.out.println(result);
                            }
                        }
                    }
                }

            } catch (MissingContextualTargetException e) {
                System.err.println("[-] Parsing Error: " + e.getMessage());
            } catch (CommandUnfulfillableException e) {
                System.err.println("[-] Error in UDP listener: " + e.getMessage());
            } catch (Exception e) {
                if (running) {
                    System.err.println("Error in UDP listener: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        this.running = false;
    }
}