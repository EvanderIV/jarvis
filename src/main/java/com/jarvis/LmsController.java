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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LmsController {

    private final String rpcUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private int requestId = 1;

    // Security credentials
    private String authHeader = null;

    // --- Security & Config ---
    private final Map<String, String> registeredSpeakers = new ConcurrentHashMap<>();
    private static final String CONFIG_FILE = "lms_speakers_config.json";

    // --- Volume Config ---
    private final Map<String, Integer> defaultVolumes = new ConcurrentHashMap<>();
    private static final String VOLUMES_FILE = "lms_volumes_config.json";

    /**
     * Initializes the controller for Logitech Media Server.
     * Pulls authentication securely from the LMS_AUTH environment variable.
     */
    public LmsController(String serverIp) {
        this.rpcUrl = "http://" + serverIp + ":9000/jsonrpc.js";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.gson = new Gson();

        // Fetch credentials from the OS environment variables
        String authEnv = System.getenv("LMS_AUTH");

        if (authEnv != null && !authEnv.trim().isEmpty()) {
            // Basic Auth expects the exact format "username:password" encoded in Base64
            String encoded = Base64.getEncoder().encodeToString(authEnv.trim().getBytes());
            this.authHeader = "Basic " + encoded;
            System.out.println("[+] Security: Loaded LMS credentials from environment.");
        } else {
            System.err.println("[-] Security Warning: LMS_AUTH environment variable is missing or empty.");
        }

        loadConfig();
        loadVolumes();
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

    public void setVolume(List<String> targetMacs, int volumePercent) {
        List<String> clients = resolveTargets(targetMacs);
        int clampedVolume = Math.max(0, Math.min(100, volumePercent));

        for (String mac : clients) {
            sendRpcRequest(mac, Arrays.asList("mixer", "volume", String.valueOf(clampedVolume)));
            System.out.println("[*] LMS: Set volume of " + mac + " to " + clampedVolume + "%");
        }
    }

    /**
     * Gets the current volume percentage of a player.
     * Returns -1 if unable to retrieve.
     */
    public int getVolume(String playerId) {
        JsonObject response = sendRpcRequest(playerId, Arrays.asList("mixer", "volume", "?"));

        if (response != null && response.has("result")) {
            JsonObject result = response.getAsJsonObject("result");
            if (result.has("_volume")) {
                try {
                    return Integer.parseInt(result.get("_volume").getAsString());
                } catch (Exception e) {
                    System.err.println("[-] Failed to parse volume: " + e.getMessage());
                }
            }
        }
        return -1;
    }

    public void playFile(List<String> targetMacs, String absoluteFilePath) {
        List<String> clients = resolveTargets(targetMacs);

        for (String mac : clients) {
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
                java.lang.reflect.Type type = new TypeToken<Map<String, String>>() {
                }.getType();
                Map<String, String> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    registeredSpeakers.putAll(loaded);
                }
                System.out.println(
                        "[+] Security: Loaded " + registeredSpeakers.size() + " registered speakers from config.");
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
            registeredSpeakers.forEach((mac, name) -> {
                int vol = getDefaultVolume(mac, -1);
                String volStr = vol >= 0 ? " (Default Vol: " + vol + "%)" : "";
                System.out.println("  - " + name + " [" + mac + "]" + volStr);
            });
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
    
    // --- Volume Config Management ---

    private void loadVolumes() {
        try {
            Path path = Paths.get(VOLUMES_FILE);
            if (Files.exists(path)) {
                String json = Files.readString(path);
                java.lang.reflect.Type type = new TypeToken<Map<String, Integer>>() {
                }.getType();
                Map<String, Integer> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    defaultVolumes.putAll(loaded);
                }
            }
        } catch (Exception e) {
            System.err.println("[-] Failed to load volumes config: " + e.getMessage());
        }
    }

    private void saveVolumes() {
        try {
            String json = gson.toJson(defaultVolumes);
            Files.writeString(Paths.get(VOLUMES_FILE), json);
        } catch (Exception e) {
            System.err.println("[-] Failed to save volumes config: " + e.getMessage());
        }
    }

    public String resolveAliasToMac(String aliasOrMac) {
        // Check if it's already a registered MAC
        if (registeredSpeakers.containsKey(aliasOrMac)) {
            return aliasOrMac;
        }
        // Search by alias (case-insensitive)
        for (Map.Entry<String, String> entry : registeredSpeakers.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(aliasOrMac)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void setDefaultVolume(String aliasOrMac, int volumePercent) {
        String mac = resolveAliasToMac(aliasOrMac);
        if (mac == null) {
            System.out.println("[-] Target not found. Ensure the speaker is registered first.");
            return;
        }
        int clamped = Math.max(0, Math.min(100, volumePercent));
        defaultVolumes.put(mac, clamped);
        saveVolumes();

        // Immediately apply the new volume to the speaker
        setVolume(Arrays.asList(mac), clamped);
        System.out.println("[+] Saved and applied default volume " + clamped + "% for '" + registeredSpeakers.get(mac) + "'");
    }

    public int getDefaultVolume(String macAddress, int fallback) {
        return defaultVolumes.getOrDefault(macAddress, fallback);
    }

    /**
     * Gets the current playback status of a player.
     * Returns a map containing playback info: "isPlaying", "currentFile",
     * "duration", "time", etc.
     */
    public Map<String, Object> getPlaybackStatus(String playerId) {
        Map<String, Object> status = new HashMap<>();
        JsonObject response = sendRpcRequest(playerId, Arrays.asList("status"));

        if (response != null && response.has("result")) {
            JsonObject result = response.getAsJsonObject("result");
            status.put("isPlaying", result.has("can_seek") && !result.get("mode").getAsString().equals("stop"));
            status.put("currentFile", result.has("current_title") ? result.get("current_title").getAsString() : null);
            status.put("mode", result.has("mode") ? result.get("mode").getAsString() : "stop");
            if (result.has("duration")) {
                status.put("duration", result.get("duration").getAsDouble());
            }
            if (result.has("time")) {
                status.put("time", result.get("time").getAsDouble());
            }
        }

        return status;
    }

    /**
     * Gets all registered speaker MAC addresses as a list.
     */
    public List<String> getAllRegisteredSpeakers() {
        return new ArrayList<>(registeredSpeakers.keySet());
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

    private List<String> getAllClientIds() {
        List<String> allMacs = new ArrayList<>();
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
     * Sends an authenticated command to the LMS JSON-RPC API.
     */
    private JsonObject sendRpcRequest(String playerId, List<String> commandArgs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", requestId++);
        payload.addProperty("method", "slim.request");

        JsonArray params = new JsonArray();
        params.add(playerId == null ? "-" : playerId);

        JsonArray cmdArray = new JsonArray();
        for (String arg : commandArgs) {
            cmdArray.add(arg);
        }
        params.add(cmdArray);
        payload.add("params", params);

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json");

            // Inject the Authorization header if we have credentials
            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader);
            }

            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Catch 401 Unauthorized before Gson tries to parse it
            if (response.statusCode() == 401) {
                System.err.println("[-] LMS RPC Error: 401 Unauthorized. Check your username and password!");
                return null;
            }

            return gson.fromJson(response.body(), JsonObject.class);

        } catch (Exception e) {
            System.err.println("[-] LMS RPC Error: " + e.toString());
            return null;
        }
    }
}