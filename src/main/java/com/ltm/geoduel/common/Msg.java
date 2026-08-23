package com.ltm.geoduel.common;

/**
 * Tên các thông điệp của giao thức. Phải luôn khớp với docs/PROTOCOL.md.
 */
public final class Msg {
    private Msg() {}

    // Client -> Server
    public static final String REGISTER = "REGISTER";
    public static final String LOGIN = "LOGIN";
    public static final String LOGOUT = "LOGOUT";
    public static final String GET_ONLINE = "GET_ONLINE";
    public static final String GET_LEADERBOARD = "GET_LEADERBOARD";
    public static final String GET_HISTORY = "GET_HISTORY";
    public static final String INVITE = "INVITE";
    public static final String INVITE_RESPONSE = "INVITE_RESPONSE";
    public static final String GUESS = "GUESS";
    public static final String LEAVE_MATCH = "LEAVE_MATCH";
    public static final String REMATCH_CHOICE = "REMATCH_CHOICE";
    public static final String PING = "PING";

    // Server -> Client
    public static final String REGISTER_RESULT = "REGISTER_RESULT";
    public static final String LOGIN_RESULT = "LOGIN_RESULT";
    public static final String ONLINE_LIST = "ONLINE_LIST";
    public static final String LEADERBOARD = "LEADERBOARD";
    public static final String HISTORY = "HISTORY";
    public static final String INVITE_INCOMING = "INVITE_INCOMING";
    public static final String INVITE_RESULT = "INVITE_RESULT";
    public static final String MATCH_START = "MATCH_START";
    public static final String ROUND_START = "ROUND_START";
    public static final String GUESS_ACK = "GUESS_ACK";
    public static final String OPPONENT_GUESSED = "OPPONENT_GUESSED";
    /** Server ép lại thời hạn lượt (vd: một bên đã nộp → bên kia chỉ còn 15 s). */
    public static final String TIMER_SYNC = "TIMER_SYNC";
    public static final String ROUND_RESULT = "ROUND_RESULT";
    public static final String MATCH_END = "MATCH_END";
    public static final String REMATCH_WAIT = "REMATCH_WAIT";
    public static final String SESSION_END = "SESSION_END";
    public static final String ERROR = "ERROR";
    public static final String PONG = "PONG";
}
