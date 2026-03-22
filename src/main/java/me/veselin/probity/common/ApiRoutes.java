package me.veselin.probity.common;

public final class ApiRoutes {

    private ApiRoutes() {}

    public static final String API = "/api";
    public static final String V1  = API + "/v1";

    public static final class Auth {
        public static final String ROOT     = V1 + "/auth";
        public static final String LOGIN    = ROOT + "/login";
        public static final String REGISTER = ROOT + "/register";
        public static final String LOGOUT   = ROOT + "/logout";
        public static final String REFRESH  = ROOT + "/refresh";
    }

    public static final class Users {
        public static final String ROOT = V1 + "/users";
        public static final String ME   = ROOT + "/me";
    }
}
