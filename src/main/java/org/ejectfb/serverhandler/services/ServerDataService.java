package org.ejectfb.serverhandler.services;

import static org.ejectfb.serverhandler.utils.StringUtils.formatDuration;

public class ServerDataService {
    private String onlinePlayers = null;
    private String tps = null;
    private String memory = null;
    private String upTime = null;
    private long serverStartTime;

    public ServerDataService() {}

    public ServerDataService(String onlinePlayers, String tps, String memory, int serverStartTime) {
        this.onlinePlayers = onlinePlayers;
        this.tps = tps;
        this.memory = memory;
        this.serverStartTime = serverStartTime;
    }

    public String parseOnlinePlayers(String consoleText) {
        String[] lines = consoleText.split("\n");

        // Ищем строку с информацией об игроках
        String matchPhrase = "[Server thread/INFO]: There are ";
        for (int i = lines.length - 1; i >= 0; i--) { // Пример строки: "There are 2/20 players online:"
            if (lines[i].contains(matchPhrase)) {
                String result = lines[i].substring(lines[i].indexOf(matchPhrase) + matchPhrase.length());
                return result.substring(0, result.indexOf(" "));
            }
        }
        return "Неизвестно количество";
    }

    public String parseMemory(String consoleText) {
        String[] lines = consoleText.split("\n");
        String matchPhrase = "Current Memory Usage: ";
        for (int i = lines.length - 1; i >= 0; i--) { // Пример строки: "There are 2/20 players online:"
            if (lines[i].contains(matchPhrase)) {
                return lines[i].substring(lines[i].indexOf(matchPhrase) + matchPhrase.length());
            }
        }
        return "Неизвестно количество";
    }

    public String parseTPS(String consoleText) {
        String[] lines = consoleText.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].contains("TPS")) {
                // Пример строки: "TPS from last 1m, 5m, 15m: 19.99, 20.00, 20.00"
                String tpsLine = lines[i];
                String[] parts = tpsLine.split(":");
                if (parts.length > 1) {
                    String[] tpsValues = parts[4].trim().split(",");
                    if (tpsValues.length >= 3) {
                        return tpsValues[0].trim();
                    }
                }
            }
        }
        return "Неизвестно...";
    }

    public String calculateUptime() {
        if (serverStartTime == 0) {
            return "N/A";
        }

        long uptimeMillis = System.currentTimeMillis() - serverStartTime;
        return formatDuration(uptimeMillis);
    }

    public void collectStatsData(String consoleText) {

        onlinePlayers = parseOnlinePlayers(consoleText);
        tps = parseTPS(consoleText);
        memory = parseMemory(consoleText);
        upTime = calculateUptime();
    }

    //Getters and Setters

    public String getOnlinePlayers() {
        return onlinePlayers;
    }

    public void setOnlinePlayers(String onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    public String getTps() {
        return tps;
    }

    public void setTps(String tps) {
        this.tps = tps;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public long getServerStartTime() {
        return this.serverStartTime;
    }

    public void setServerStartTime(long serverStartTime) {
        this.serverStartTime = serverStartTime;
    }

    public String getUpTime() {
        return upTime;
    }

    public void setUpTime(String upTime) {
        this.upTime = upTime;
    }
}