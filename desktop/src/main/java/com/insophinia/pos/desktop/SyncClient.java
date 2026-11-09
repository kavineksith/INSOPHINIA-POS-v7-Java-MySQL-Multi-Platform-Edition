package com.insophinia.pos.desktop;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/** Subscribes to /api/sync/stream (SSE) and delivers raw event lines to the UI thread. */
public class SyncClient {

    private volatile boolean running;
    private Thread thread;

    public void start(Consumer<String> onEvent) { startPath("/api/sync/stream", null, onEvent); }

    public void startDisplay(String channel, Consumer<String> onEvent) {
        if (channel == null || channel.length() < 16) return;
        startPath("/api/sync/customer-display/stream", channel, onEvent);
    }

    private void startPath(String path, String displayChannel, Consumer<String> onEvent) {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(Session.validatedBaseUrl() + path))
                            .header("Authorization", "Bearer " + Session.token)
                            .header("Accept", "text/event-stream")
                            .header("X-Display-Channel", displayChannel == null ? "" : displayChannel)
                            .build();
                    var response = HttpClient.newHttpClient()
                            .send(req, HttpResponse.BodyHandlers.ofInputStream());
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body()))) {
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String payload = line.substring(5);
                                Platform.runLater(() -> onEvent.accept(payload));
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!running) return;
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "sync-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
