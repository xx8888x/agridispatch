package com.agridispatch.app;

import android.view.View;
import android.view.Window;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * targetSdk 35 起 Android 15 強制滿版 (edge-to-edge)，內容會畫到狀態列、手勢列與瀏海底下。
 * 以系統列、瀏海與鍵盤的 insets 設定容器 padding，避免網頁頂部被狀態列遮住、
 * 底部被手勢列遮住，並讓鍵盤彈出時輸入框不被蓋住 (取代 adjustResize 在滿版下的失效)。
 */
public final class SystemBarInsets {

    private SystemBarInsets() {
    }

    public static void apply(Window window, View container) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
