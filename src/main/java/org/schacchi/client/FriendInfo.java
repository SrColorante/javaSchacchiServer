package org.schacchi.client;

public class FriendInfo {
    private final String username;
    private final boolean online;

    public FriendInfo(String username, boolean online) {
        this.username = username;
        this.online = online;
    }

    public String getUsername() {
        return username;
    }

    public boolean isOnline() {
        return online;
    }

    @Override
    public String toString() {
        return username + (online ? " [ONLINE]" : " [OFFLINE]");
    }
}
