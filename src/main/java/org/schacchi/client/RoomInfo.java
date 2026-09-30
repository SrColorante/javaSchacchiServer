package org.schacchi.client;

public class RoomInfo {
    private final String roomId;
    private final String roomName;
    private final String hostName;

    public RoomInfo(String roomId, String roomName, String hostName) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.hostName = hostName;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getHostName() {
        return hostName;
    }

    @Override
    public String toString() {
        return roomName + " (ID: " + roomId + ", Host: " + hostName + ")";
    }
}
