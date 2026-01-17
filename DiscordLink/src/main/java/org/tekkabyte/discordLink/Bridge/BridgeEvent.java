package org.tekkabyte.discordLink.Bridge;

public class BridgeEvent {
    public String type;
    public String author;
    public String content;
    public String command;
    public String by;

    public boolean isChat() { return "chat".equalsIgnoreCase(type); }
    public boolean isCommand() { return "command".equalsIgnoreCase(type); }
    public boolean isOnline() { return "online".equalsIgnoreCase(type); }
}
