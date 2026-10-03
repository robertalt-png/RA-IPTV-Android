package com.nenotv.player.model;

public class Profile {
    public enum Type { XTREAM, M3U }
    public Type type = Type.XTREAM;
    public String name = "My IPTV";
    public String server = "";
    public String username = "";
    public String password = "";
    public String m3uUrl = "";
    public String epgUrl = "";
    public String bridgeUrl = "";
    public String bridgeToken = "";
}
