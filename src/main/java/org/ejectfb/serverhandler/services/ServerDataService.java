package org.ejectfb.serverhandler.services;

public class ServerDataService {
    private String onlinePlayers = null;
    private String tps = null;
    private String memory = null;

    public ServerDataService() {}

    public ServerDataService(String onlinePlayers, String tps, String memory) {
        this.onlinePlayers = onlinePlayers;
        this.tps = tps;
        this.memory = memory;
    }

    public boolean isComplete() {
        return onlinePlayers != null && tps != null && memory != null;
    }

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
}