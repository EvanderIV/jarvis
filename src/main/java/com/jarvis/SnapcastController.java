package com.jarvis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.reflect.TypeToken;

public class SnapcastController {

    private final String rpcUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private int requestId = 1; // Snapcast likes an incrementing ID for requests

    // --- Security & Config ---
    private final Map<String, String> registeredSpeakers = new ConcurrentHashMap<>();
    private static final String CONFIG_FILE = "speakers_config.json";

    /**
     * Initializes the controller.
     * @param serverIp The IP address of your Linux machine running Snapcast (e.g., "127.0.0.1" or "192.168.1.50")
     */
    public SnapcastController(String serverIp) {
        // Snapcast defaults to port 1780 for its HTTP JSON-RPC interface
        this.rpcUrl = "http://" + serverIp + ":1780/jsonrpc";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.gson = new Gson();
        
        // Load the registered speakers from disk on startup
        loadConfig();
    }

    // --- Core Action Methods ---

    public void mute(List<String> targetMacs) {
        setMuteState(targetMacs, true);
    }

    public void unmute(List<String> targetMacs) {
        setMuteState(targetMacs, false);
    }

    /**
     * Sets the volume for the specified speakers.
     * @param targetMacs List of MAC addresses. If null or empty, applies to ALL speakers.
     * @param volumePercent Integer from 0 to 100.
     */
    public void setVolume(List<String> targetMacs, int volumePercent) {
        List<String> clients = resolveTargets(targetMacs);
        
        for (String mac : clients) {
            JsonObject params = new JsonObject();
            params.addProperty("id", mac);
            
            JsonObject volumeObj = new JsonObject();
            volumeObj.addProperty("percent", Math.max(0, Math.min(100, volumePercent))); // Clamp between 0-100
            params.add("volume", volumeObj);

            sendRpcRequest("Client.SetVolume", params);
            System.out.println("[*] Snapcast: Set volume of " + mac + " to " + volumePercent + "%");
        }
    }

    /**
     * Routes the specified speakers to listen to a specific audio pipe (e.g., "Bedroom", "House")
     * @param targetMacs List of MAC addresses. If null or empty, applies to ALL speakers.
     * @param streamId The name of the stream defined in snapserver.conf
     */
    public void playStream(List<String> targetMacs, String streamId) {
        List<String> clients = resolveTargets(targetMacs);
        
        for (String mac : clients) {
            JsonObject params = new JsonObject();
            params.addProperty("id", mac);
            params.addProperty("stream_id", streamId);

            sendRpcRequest("Client.SetStream", params);
            System.out.println("[*] Snapcast: Routed " + mac + " to stream '" + streamId + "'");
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
        System.out.println("\n[*] Scanning for connected but unregistered speakers...");
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
        
        for (String mac : clients) {
            JsonObject params = new JsonObject();
            params.addProperty("id", mac);
            
            JsonObject volumeObj = new JsonObject();
            volumeObj.addProperty("muted", isMuted);
            params.add("volume", volumeObj);

            sendRpcRequest("Client.SetVolume", params);
            System.out.println("[*] Snapcast: Muted state for " + mac + " set to " + isMuted);
        }
    }

    /**
     * Determines if we should use the provided list or fetch all connected clients,
     * while enforcing security by filtering out unregistered MAC addresses.
     */
    private List<String> resolveTargets(List<String> targetMacs) {
        List<String> validTargets = new ArrayList<>();
        
        if (targetMacs == null || targetMacs.isEmpty()) {
            // Apply to ALL CONNECTED AND REGISTERED speakers
            List<String> allConnected = getAllClientIds();
            for (String mac : allConnected) {
                if (registeredSpeakers.containsKey(mac)) {
                    validTargets.add(mac);
                }
            }
        } else {
            // Ensure specifically requested targets are actually registered
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
     * Queries the server for its status and extracts every connected speaker's MAC address.
     */
    private List<String> getAllClientIds() {
        List<String> allMacs = new ArrayList<>();
        
        JsonObject response = sendRpcRequest("Server.GetStatus", new JsonObject());
        if (response != null && response.has("result")) {
            JsonObject result = response.getAsJsonObject("result");
            JsonObject server = result.getAsJsonObject("server");
            JsonArray groups = server.getAsJsonArray("groups");
            
            // Iterate through the Snapcast routing groups to find all clients
            for (int i = 0; i < groups.size(); i++) {
                JsonArray clients = groups.get(i).getAsJsonObject().getAsJsonArray("clients");
                for (int j = 0; j < clients.size(); j++) {
                    JsonObject client = clients.get(j).getAsJsonObject();
                    allMacs.add(client.get("id").getAsString());
                }
            }
        }
        return allMacs;
    }

    /**
     * The core network function that actually sends the JSON payload to Snapcast.
     */
    private JsonObject sendRpcRequest(String method, JsonObject params) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", requestId++);
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("method", method);
        if (params != null && params.size() > 0) {
            payload.add("params", params);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return gson.fromJson(response.body(), JsonObject.class);
            
        } catch (Exception e) {
            System.err.println("[-] Snapcast RPC Error: " + e.getMessage());
            return null;
        }
    }
}