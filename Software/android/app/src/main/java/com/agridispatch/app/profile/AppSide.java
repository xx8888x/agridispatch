package com.agridispatch.app.profile;

import com.agridispatch.app.BuildConfig;

/**
 * App 的「端」：由 Gradle product flavor 決定 (BuildConfig.APP_SIDE)。
 * SERVER = 科技後台 (科技公司代管人員)；CLIENT = 農場主與工人。
 */
public enum AppSide {
    SERVER("server"),
    CLIENT("client");

    public final String code;

    AppSide(String code) {
        this.code = code;
    }

    public static AppSide current() {
        return "server".equals(BuildConfig.APP_SIDE) ? SERVER : CLIENT;
    }
}
