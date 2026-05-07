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