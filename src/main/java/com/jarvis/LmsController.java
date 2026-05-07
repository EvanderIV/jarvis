package com.jarvis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LmsController {

    private final String rpcUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private int requestId = 1;

    // --- Security & Config ---
    private final Map<String, String> registeredSpeakers = new ConcurrentHashMap<>();
    private static final String CONFIG_FILE = "lms_speakers_config.json";

    /**
     * Initializes the controller for Logitech Media Server.
     * @param serverIp The IP address of your Linux machine running LMS (e.g., "127.0.0.1" or "192.168.1.50")
     */
    public LmsController(String serverIp) {
        // LMS hosts its JSON-RPC API on port 9000 at the /jsonrpc.js endpoint
        this.rpcUrl = "http://" + serverIp + ":9000/jsonrpc.js";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.gson = new Gson();
        
        loadConfig();
    }

    // --- Core Action Methods ---

    public void mute(List<String> targetMacs) {
        setMuteState(targetMacs, true);
    }

    public void unmute(List<String> targetMacs) {
        setMuteState(targetMacs, false);
    }

    public void stop(List<String> targetMacs) {
        List<String> clients = resolveTargets(targetMacs);
        for (String mac : clients) {
            sendRpcRequest(mac, Arrays.asList("stop"));
            System.out.println("[*] LMS: Stopped playback on " + mac);
        }
    }

    /**
     * Sets the volume for the specified speakers.
     * @param targetMacs List of MAC addresses. If null or empty, applies to ALL speakers.
     * @param volumePercent Integer from 0 to 100.
     */
    public void setVolume(List<String> targetMacs, int volumePercent) {
        List<String> clients = resolveTargets(targetMacs);
        int clampedVolume = Math.max(0, Math.min(100, volumePercent));
        
        for (String mac : clients) {
            sendRpcRequest(mac, Arrays.asList("mixer", "volume", String.valueOf(clampedVolume)));
            System.out.println("[*] LMS: Set volume of " + mac + " to " + clampedVolume + "%");
        }
    }

    /**
     * Instructs LMS to stream a specific audio file directly to the speakers.
     * @param targetMacs List of MAC addresses. If null or empty, applies to ALL speakers.
     * @param absoluteFilePath The absolute path to the .mp3/.flac file on the Linux server.
     */
    public void playFile(List<String> targetMacs, String absoluteFilePath) {
        List<String> clients = resolveTargets(targetMacs);
        
        for (String mac : clients) {
            // "playlist play <file>" replaces the current queue and plays the file instantly
            sendRpcRequest(mac, Arrays.asList("playlist", "play", absoluteFilePath));
            System.out.println("[*] LMS: Instructed " + mac + " to play '" + absoluteFilePath + "'");
        }
    }

    // --- Speaker Management & Security ---

    private void loadConfig() {
        try {
            Path path = Paths.get(CONFIG_FILE);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                java.lang.reflect.Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    registeredSpeakers.putAll(loaded);
                }
                System.out.println("[+] Security: Loaded " + registeredSpeakers.size() + " registered speakers from config.");
            }
        } catch (Exception e) {
            System.err.println("[-] Failed to load speakers config: " + e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            String json = gson.toJson(registeredSpeakers);
            Files.writeString(Paths.get(CONFIG_FILE), json);
        } catch (Exception e) {
            System.err.println("[-] Failed to save speakers config: " + e.getMessage());
        }
    }

    public void registerSpeaker(String macAddress, String alias) {
        registeredSpeakers.put(macAddress, alias);
        saveConfig();
        System.out.println("[+] Security: Registered speaker '" + alias + "' (" + macAddress + ")");
    }

    public void removeSpeaker(String macAddress) {
        if (registeredSpeakers.remove(macAddress) != null) {
            saveConfig();
            System.out.println("[+] Security: Removed speaker (" + macAddress + ")");
        } else {
            System.out.println("[-] Security: Speaker " + macAddress + " not found in registry.");
        }
    }

    public void listRegisteredSpeakers() {
        System.out.println("\n--- Registered Speakers ---");
        if (registeredSpeakers.isEmpty()) {
            System.out.println("  (None)");
        } else {
            registeredSpeakers.forEach((mac, name) -> System.out.println("  - " + name + " [" + mac + "]"));
        }
        System.out.println("---------------------------\n");
    }

    public void scanForUnregisteredSpeakers() {
        System.out.println("\n[*] Scanning for connected but unregistered LMS players...");
        List<String> allConnected = getAllClientIds();
        boolean found = false;
        for (String mac : allConnected) {
            if (!registeredSpeakers.containsKey(mac)) {
                System.out.println("  [!] Found unregistered device: " + mac);
                found = true;
            }
        }
        if (!found) {
            System.out.println("  (All connected speakers are registered)");
        }
        System.out.println("---------------------------\n");
    }

    // --- Helper Methods ---

    private void setMuteState(List<String> targetMacs, boolean isMuted) {
        List<String> clients = resolveTargets(targetMacs);
        String muteState = isMuted ? "1" : "0";
        
        for (String mac : clients) {
            sendRpcRequest(mac, Arrays.asList("mixer", "muting", muteState));
            System.out.println("[*] LMS: Muted state for " + mac + " set to " + isMuted);
        }
    }

    private List<String> resolveTargets(List<String> targetMacs) {
        List<String> validTargets = new ArrayList<>();
        
        if (targetMacs == null || targetMacs.isEmpty()) {
            List<String> allConnected = getAllClientIds();
            for (String mac : allConnected) {
                if (registeredSpeakers.containsKey(mac)) {
                    validTargets.add(mac);
                }
            }
        } else {
            for (String mac : targetMacs) {
                if (registeredSpeakers.containsKey(mac)) {
                    validTargets.add(mac);
                } else {
                    System.out.println("[-] Security Warning: Ignored command for unregistered speaker: " + mac);
                }
            }
        }
        return validTargets;
    }

    /**
     * Queries LMS for all currently connected speakers (players).
     */
    private List<String> getAllClientIds() {
        List<String> allMacs = new ArrayList<>();
        
        // "-" is the wildcard player ID used to query server-wide status
        JsonObject response = sendRpcRequest("-", Arrays.asList("serverstatus", "0", "99"));
        
        if (response != null && response.has("result")) {
            JsonObject result = response.getAsJsonObject("result");
            if (result.has("players_loop")) {
                JsonArray players = result.getAsJsonArray("players_loop");
                for (int i = 0; i < players.size(); i++) {
                    JsonObject player = players.get(i).getAsJsonObject();
                    allMacs.add(player.get("playerid").getAsString());
                }
            }
        }
        return allMacs;
    }

    /**
     * Sends a command to the LMS JSON-RPC API using the "slim.request" protocol.
     */
    private JsonObject sendRpcRequest(String playerId, List<String> commandArgs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", requestId++);
        payload.addProperty("method", "slim.request");

        // The params array looks like: [ "player_mac", [ "command", "arg1" ] ]
        JsonArray params = new JsonArray();
        params.add(playerId == null ? "-" : playerId);
        
        JsonArray cmdArray = new JsonArray();
        for (String arg : commandArgs) {
            cmdArray.add(arg);
        }
        params.add(cmdArray);
        
        payload.add("params", params);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return gson.fromJson(response.body(), JsonObject.class);
            
        } catch (Exception e) {
            System.err.println("[-] LMS RPC Error: " + e.getMessage());
            return null;
        }
    }
}