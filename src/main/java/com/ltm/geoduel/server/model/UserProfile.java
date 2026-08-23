package com.ltm.geoduel.server.model;

/** Hồ sơ người chơi đọc từ bảng users. */
public class UserProfile {
    public int id;
    public String username;
    public String displayName;
    public int elo;
    public int wins;
    public int losses;
    public int draws;
    public long totalScore;
}
