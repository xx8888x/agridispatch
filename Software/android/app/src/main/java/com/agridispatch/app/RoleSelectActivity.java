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

    private LinearLayout list;
    private boolean onSubStep = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.slate_950));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(36), pad, pad);
        scroll.addView(list);
        setContentView(scroll);

        showRoles();
    }

    private void showRoles() {
        onSubStep = false;
        list.removeAllViews();
        addHeader(getString(R.string.app_name), getString(R.string.role_pick_title));
        for (ClientRole role : ClientRole.values()) {
            addOption(getString(role.labelRes), getString(role.descRes), true, v -> {
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
        addHeader(getString(R.string.role_pick_worker_local), getString(R.string.local_pick_title));
        for (LocalWorkerType type : LocalWorkerType.values()) {
            addOption(getString(type.labelRes), getString(type.descRes), true,
                    v -> finishWith(UserProfile.localWorker(type)));
        }
    }

    private void showNationalities() {
        onSubStep = true;
        list.removeAllViews();
        addBack();
        addHeader(getString(R.string.role_pick_worker_foreign), getString(R.string.nation_pick_title));
        for (Nationality n : Nationality.values()) {
            addOption(n.label, getString(n.descRes), n.offered,
                    v -> finishWith(UserProfile.foreignWorker(n)));
        }
        addFootnote(getString(R.string.nation_equal_pay));
    }

    private void finishWith(UserProfile profile) {
        ProfileStore.save(this, profile);
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

    private void addHeader(String title, String subtitle) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.white));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(ContextCompat.getColor(this, R.color.slate_400));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setPadding(0, dp(6), 0, dp(20));
        list.addView(s);
    }

    private void addBack() {
        TextView b = new TextView(this);
        b.setText(R.string.role_pick_back);
        b.setTextColor(ContextCompat.getColor(this, R.color.brand_primary));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(0, dp(4), 0, dp(16));
        b.setOnClickListener(v -> showRoles());
        list.addView(b);
    }

    private void addOption(String title, String desc, boolean enabled, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
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

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.white));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(ContextCompat.getColor(this, R.color.slate_400));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        list.addView(card, lp);
    }

    private void addFootnote(String text) {
        TextView f = new TextView(this);
        f.setText(text);
        f.setTextColor(ContextCompat.getColor(this, R.color.slate_400));
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        f.setGravity(Gravity.START);
        f.setPadding(0, dp(8), 0, 0);
        list.addView(f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
