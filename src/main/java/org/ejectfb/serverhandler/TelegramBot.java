package org.ejectfb.serverhandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramBot {
    private final String botToken;
    private final String chatId;

    public TelegramBot(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
    }

    public void sendMessage(String text) {
        new Thread(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String jsonInputString = String.format(
                        "{\"chat_id\": \"%s\", \"text\": \"%s\"}",
                        chatId,
                        text.replace("\"", "\\\"")
                );

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}