package com.jarvis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

public class NestListener implements Runnable {

    private static final String CONFIG_FILE = "nest_config.json";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String PERSON_EVENT  = "sdm.devices.events.CameraPerson.Event";
    private static final String MOTION_EVENT  = "sdm.devices.events.CameraMotion.Event";
    private static final String SOUND_EVENT   = "sdm.devices.events.CameraSound.Event";
    private static final String CHIME_EVENT   = "sdm.devices.events.DoorbellChime.Event";
    private static final int POLL_INTERVAL_MS = 5000;
    private static final int TOKEN_REFRESH_BUFFER_MS = 60_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private volatile boolean running = true;

    private final MusicManager musicManager;
    private final LmsController lmsController;
    private NestConfig config;

    public NestListener(MusicManager musicManager, LmsController lmsController) {
        this.musicManager = musicManager;
        this.lmsController = lmsController;
    }

    static class NestConfig {
        String clientId = "";
        String clientSecret = "";
        String gcpProjectId = "";
        String deviceAccessProjectId = "";
        String subscriptionId = "";
        String refreshToken = "";
        String accessToken = "";
        long tokenExpiryMs = 0;
    }

    private boolean loadConfigFile() {
        try {
            if (!Files.exists(Paths.get(CONFIG_FILE))) {
                System.out.println("[-] NestListener: nest_config.json not found.");
                return false;
            }
            config = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), NestConfig.class);
            String envSecret = System.getenv("NEST_CLIENT_SECRET");
            if (envSecret != null && !envSecret.isEmpty()) {
                config.clientSecret = envSecret;
            } else if (config.clientSecret == null || config.clientSecret.isEmpty()) {
                System.err.println("[-] NestListener: No client secret — set NEST_CLIENT_SECRET env var.");
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[-] NestListener: Failed to load config: " + e.getMessage());
            return false;
        }
    }

    public boolean loadConfig() {
        if (!loadConfigFile()) return false;
        if (config.refreshToken == null || config.refreshToken.isEmpty()) {
            System.out.println("[-] NestListener: No refresh token. Use 'nest-setup' then 'nest-auth <code>'.");
            return false;
        }
        return true;
    }

    public void printAuthUrl() {
        if (!loadConfigFile()) return;
        System.out.println("[*] Open this URL in a browser, sign in, and grant permissions:");
        System.out.println("    " + getAuthUrl());
        System.out.println("[*] After redirecting to google.com, copy the 'code=' value from the URL.");
        System.out.println("[*] Then run: nest-auth <code>");
    }

    private void saveConfig() {
        try {
            Files.writeString(Paths.get(CONFIG_FILE), gson.toJson(config));
        } catch (IOException e) {
            System.err.println("[-] NestListener: Failed to save config: " + e.getMessage());
        }
    }

    public String getAuthUrl() {
        return "https://nestservices.google.com/partnerconnections/"
                + config.deviceAccessProjectId + "/auth"
                + "?redirect_uri=https://www.google.com"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&client_id=" + config.clientId
                + "&response_type=code"
                + "&scope=https://www.googleapis.com/auth/sdm.service";
    }

    public void exchangeAuthCode(String code) {
        if (config == null && !loadConfigFile()) return;
        try {
            String body = "client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8)
                    + "&code=" + URLEncoder.encode(code.trim(), StandardCharsets.UTF_8)
                    + "&grant_type=authorization_code"
                    + "&redirect_uri=https://www.google.com";

            JsonObject response = postForm(TOKEN_URL, body);
            if (response.has("refresh_token")) {
                config.refreshToken = response.get("refresh_token").getAsString();
                config.accessToken = response.get("access_token").getAsString();
                config.tokenExpiryMs = System.currentTimeMillis()
                        + (response.get("expires_in").getAsLong() * 1000);
                saveConfig();
                System.out.println("[+] NestListener: Authorization successful — tokens saved to nest_config.json.");
                activateEvents();
            } else {
                System.err.println("[-] NestListener: Auth exchange failed: " + gson.toJson(response));
            }
        } catch (Exception e) {
            System.err.println("[-] NestListener: Auth exchange error: " + e.getMessage());
        }
    }

    // Required by SDM API — events are not published until devices.list is called at least once.
    private void activateEvents() {
        try {
            ensureValidToken();
            String url = "https://smartdevicemanagement.googleapis.com/v1/enterprises/"
                    + config.deviceAccessProjectId + "/devices";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[+] NestListener: devices.list status " + response.statusCode());
            if (response.statusCode() == 200) {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (body.has("devices")) {
                    JsonArray devices = body.getAsJsonArray("devices");
                    System.out.println("[+] NestListener: " + devices.size() + " device(s) registered:");
                    for (int i = 0; i < devices.size(); i++) {
                        JsonObject device = devices.get(i).getAsJsonObject();
                        String name = device.get("name").getAsString();
                        String type = device.has("type") ? device.get("type").getAsString() : "unknown";
                        System.out.println("    - " + type + " | " + name);
                    }
                } else {
                    System.out.println("[-] NestListener: No devices found. Is the doorbell linked to this account?");
                }
            } else {
                System.out.println("[-] NestListener: devices.list failed: " + response.body());
            }
        } catch (Exception e) {
            System.err.println("[-] NestListener: Event activation call failed: " + e.getMessage());
        }
    }

    public void verify() {
        if (config == null && !loadConfigFile()) return;
        System.out.println("[*] NestListener: Running verification...");
        activateEvents();
    }

    private void refreshAccessToken() {
        try {
            String body = "client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(config.refreshToken, StandardCharsets.UTF_8)
                    + "&grant_type=refresh_token";

            JsonObject response = postForm(TOKEN_URL, body);
            if (response.has("access_token")) {
                config.accessToken = response.get("access_token").getAsString();
                config.tokenExpiryMs = System.currentTimeMillis()
                        + (response.get("expires_in").getAsLong() * 1000);
                saveConfig();
                System.out.println("[*] NestListener: Access token refreshed.");
            } else {
                System.err.println("[-] NestListener: Token refresh failed: " + gson.toJson(response));
            }
        } catch (Exception e) {
            System.err.println("[-] NestListener: Token refresh error: " + e.getMessage());
        }
    }

    private JsonObject postForm(String url, String formBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void ensureValidToken() {
        if (System.currentTimeMillis() >= config.tokenExpiryMs - TOKEN_REFRESH_BUFFER_MS) {
            refreshAccessToken();
        }
    }

    @Override
    public void run() {
        if (!loadConfig()) return;

        String pullUrl = "https://pubsub.googleapis.com/v1/projects/"
                + config.gcpProjectId + "/subscriptions/" + config.subscriptionId + ":pull";
        String ackUrl = pullUrl.replace(":pull", ":acknowledge");

        System.out.println("[+] NestListener: Polling Pub/Sub for doorbell events...");

        while (running) {
            try {
                ensureValidToken();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(pullUrl))
                        .header("Authorization", "Bearer " + config.accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"maxMessages\":10}"))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    System.out.println("[*] NestListener: 401 — forcing token refresh.");
                    refreshAccessToken();
                } else if (response.statusCode() == 200) {
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (body.has("receivedMessages")) {
                        JsonArray messages = body.getAsJsonArray("receivedMessages");
                        JsonArray ackIds = new JsonArray();

                        for (int i = 0; i < messages.size(); i++) {
                            JsonObject msg = messages.get(i).getAsJsonObject();
                            ackIds.add(msg.get("ackId").getAsString());
                            String data = new String(Base64.getDecoder().decode(
                                    msg.getAsJsonObject("message").get("data").getAsString()));
                            handleEvent(data);
                        }

                        JsonObject ackBody = new JsonObject();
                        ackBody.add("ackIds", ackIds);
                        HttpRequest ackRequest = HttpRequest.newBuilder()
                                .uri(URI.create(ackUrl))
                                .header("Authorization", "Bearer " + config.accessToken)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(ackBody)))
                                .build();
                        http.send(ackRequest, HttpResponse.BodyHandlers.ofString());
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) System.err.println("[-] NestListener poll error: " + e.getMessage());
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void handleEvent(String data) {
        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            if (!event.has("resourceUpdate")) return;
            JsonObject events = event.getAsJsonObject("resourceUpdate").getAsJsonObject("events");
            if (events == null) return;

            if (events.has(CHIME_EVENT)) {
                System.out.println("[+] NestListener: Doorbell pressed!");
            }
            if (events.has(PERSON_EVENT)) {
                System.out.println("[+] NestListener: Person detected at front door!");
                onPersonDetected();
            } else if (events.has(MOTION_EVENT)) {
                System.out.println("[+] NestListener: Motion detected at front door.");
            } else if (events.has(SOUND_EVENT)) {
                System.out.println("[+] NestListener: Sound detected at front door.");
            }
        } catch (Exception e) {
            System.err.println("[-] NestListener: Failed to parse event: " + e.getMessage());
        }
    }

    private void onPersonDetected() {
        List<String> allSpeakers = lmsController.getAllRegisteredSpeakers();
        musicManager.playMusic("default", allSpeakers);
        for (String mac : allSpeakers) {
            int speakerMax = lmsController.getDefaultVolume(mac, 60);
            lmsController.setVolume(List.of(mac), (int) Math.round(0.7 * speakerMax));
        }
    }

    public void stop() {
        this.running = false;
    }
}
