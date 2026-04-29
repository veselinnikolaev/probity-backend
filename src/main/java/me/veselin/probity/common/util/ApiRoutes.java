package me.veselin.probity.common.util;

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
        public static final String CSRF = ROOT + "/csrf";
    }

    public static final class Users {
        public static final String ROOT = V1 + "/users";
        public static final String ME   = ROOT + "/me";
    }

    public static final class Portfolios {
        public static final String PORTFOLIOS = V1 + "/portfolios";
        public static final String PORTFOLIO = PORTFOLIOS + "/{id}"  ;
        public static final String VOLATILITY = PORTFOLIO + "/volatility";
        public static final String ALERTS = PORTFOLIO + "/alerts";
        public static final String COMPOSITION = PORTFOLIO + "/composition";
        public static final String SUMMARY = PORTFOLIO + "/summary";
        public static final String POSITIONS = PORTFOLIO + "/positions";
        public static final String RISK_METRICS = PORTFOLIO + "/risk-metrics";
        public static final String CORRELATION  = PORTFOLIO + "/correlation";
        public static final String POSITION = PORTFOLIO + "/positions/{positionId}";
    }

    public static final class Assets {
        public static final String ASSETS = V1 + "/assets";
        public static final String SEARCH = ASSETS + "/search";
    }
}
