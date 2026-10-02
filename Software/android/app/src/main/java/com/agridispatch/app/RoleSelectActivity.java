package com.agridispatch.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.agridispatch.app.profile.ClientRole;
import com.agridispatch.app.profile.LocalWorkerType;
import com.agridispatch.app.profile.Nationality;
import com.agridispatch.app.profile.ProfileStore;
import com.agridispatch.app.profile.UserProfile;

/**
 * 智農派工 (AgriDispatch) - 客戶端原生身分選擇畫面
 * 第一步：農場主 / 本地工人 (本勞) / 外籍移工 (外勞)
 * 第二步：本勞選類型 (中高齡、二度就業、大專生、帶班師傅、機耕師)；外勞選國別 (越南、印尼；泰國、菲律賓未開放)
 * 選定後寫入 ProfileStore 並開啟 MainActivity 載入對應登入頁。僅客戶端 (client flavor) 宣告此 Activity。
 */
public class RoleSelectActivity extends AppCompatActivity {

    /** 由「切換角色」開啟時為 true：即使已選過身分也顯示選擇畫面 */
    public static final String EXTRA_CHOOSE = "com.agridispatch.app.extra.CHOOSE_ROLE";

    private LinearLayout list;
    private boolean onSubStep = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 從桌面啟動且已選過身分：直接進入對應頁面
        if (!getIntent().getBooleanExtra(EXTRA_CHOOSE, false) && ProfileStore.load(this) != null) {
            openMain();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.slate_950));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(16), pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        SystemBarInsets.apply(getWindow(), scroll);

        showRoles();
    }

    private void showRoles() {
        onSubStep = false;
        list.removeAllViews();
        addHeader(null, getString(R.string.app_name), getString(R.string.role_pick_title));
        for (ClientRole role : ClientRole.values()) {
            addOption(role.icon, getString(role.labelRes), getString(role.descRes), true, v -> {
                if (role == ClientRole.FARMER) {
                    finishWith(UserProfile.farmer());
                } else if (role == ClientRole.WORKER_LOCAL) {
                    showLocalTypes();
                } else {
                    showNationalities();
                }
            });
        }
    }

    private void showLocalTypes() {
        onSubStep = true;
        list.removeAllViews();
        addBack();
        ClientRole role = ClientRole.WORKER_LOCAL;
        addHeader(role.icon, getString(role.labelRes), getString(R.string.local_pick_title));
        for (LocalWorkerType type : LocalWorkerType.values()) {
            addOption(type.icon, getString(type.labelRes), getString(type.descRes), true,
                    v -> finishWith(UserProfile.localWorker(type)));
        }
    }

    private void showNationalities() {
        onSubStep = true;
        list.removeAllViews();
        addBack();
        ClientRole role = ClientRole.WORKER_FOREIGN;
        addHeader(role.icon, getString(role.labelRes), getString(R.string.nation_pick_title));
        for (Nationality n : Nationality.values()) {
            addOption(n.flag, n.label, getString(n.descRes), n.offered,
                    v -> finishWith(UserProfile.foreignWorker(n)));
        }
        addFootnote(getString(R.string.nation_equal_pay));
    }

    private void finishWith(UserProfile profile) {
        ProfileStore.save(this, profile);
        openMain();
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (onSubStep) {
            showRoles();
        } else {
            super.onBackPressed();
        }
    }

    // ---- 版面元件 ----
    // 卡片統一為「固定寬度圖示欄｜標題＋說明｜右箭頭」三欄，所有文字左緣對齊。

    private void addHeader(String icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            TextView i = text(icon, 24, R.color.white, false);
            i.setPadding(0, 0, dp(10), 0);
            row.addView(i);
        }
        row.addView(text(title, 24, R.color.white, true));
        list.addView(row);

        TextView s = text(subtitle, 15, R.color.slate_400, false);
        s.setPadding(0, dp(6), 0, dp(20));
        list.addView(s);
    }

    private void addBack() {
        TextView b = text(getString(R.string.role_pick_back), 15, R.color.brand_primary, false);
        b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> showRoles());
        list.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addOption(String icon, String title, String desc, boolean enabled, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(84));
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(ContextCompat.getColor(this, R.color.slate_900));
        bg.setStroke(dp(1), ContextCompat.getColor(this, enabled ? R.color.brand_dark : R.color.slate_700));
        card.setBackground(bg);
        card.setAlpha(enabled ? 1f : 0.45f);
        card.setEnabled(enabled);
        if (enabled) {
            card.setClickable(true);
            card.setOnClickListener(onClick);
        }

        TextView iconView = text(icon, 26, R.color.white, false);
        iconView.setGravity(Gravity.CENTER);
        card.addView(iconView, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(text(title, 17, R.color.white, true));
        TextView d = text(desc, 13, R.color.slate_400, false);
        d.setPadding(0, dp(4), 0, 0);
        d.setLineSpacing(dp(2), 1f);
        texts.addView(d);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text(enabled ? "›" : "", 26, R.color.slate_400, false);
        chevron.setGravity(Gravity.CENTER);
        card.addView(chevron, new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        list.addView(card, lp);
    }

    private void addFootnote(String note) {
        TextView f = text(note, 12, R.color.slate_400, false);
        f.setPadding(dp(4), dp(8), dp(4), 0);
        f.setLineSpacing(dp(2), 1f);
        list.addView(f);
    }

    private TextView text(String value, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(ContextCompat.getColor(this, colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
