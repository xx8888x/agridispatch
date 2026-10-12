package com.agridispatch.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.agridispatch.app.profile.ClientRole;
import com.agridispatch.app.profile.LocalTrack;
import com.agridispatch.app.profile.Nationality;
import com.agridispatch.app.profile.ProfileStore;
import com.agridispatch.app.profile.UserProfile;

/**
 * 智農派工 (AgriDispatch) - 客戶端原生身分選擇畫面
 * 依設計系統「不讓使用者思考」(文件/07_系統分析及文件/04_UI設計系統規範.md)：
 * 第一頁只問「你是誰」：農場主／本國工人／各國外籍工人 (外籍卡片標題用工人母語，一次點選就完成)；
 * 外籍工人卡片目前下架 (FeatureFlags.FOREIGN_WORKERS=false)，只顯示農場主與本國工人；
 * 本國工人才有第二頁「做哪種工作」(一般農務／帶班師傅／農機操作，三者進不同頁面)。
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
        scroll.setBackgroundColor(color(R.color.ad_bg));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(24), pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        SystemBarInsets.apply(getWindow(), scroll);

        showRoles();
    }

    private void showRoles() {
        onSubStep = false;
        list.removeAllViews();
        addHeader(getString(R.string.app_name), getString(R.string.role_pick_title));
        addOption(iconView(ClientRole.FARMER.iconRes), getString(ClientRole.FARMER.labelRes),
                getString(ClientRole.FARMER.descRes), v -> finishWith(UserProfile.farmer()));
        addOption(iconView(ClientRole.WORKER_LOCAL.iconRes), getString(ClientRole.WORKER_LOCAL.labelRes),
                getString(ClientRole.WORKER_LOCAL.descRes), v -> showLocalTracks());
        for (Nationality n : Nationality.values()) {
            if (n.selectable()) { // 外籍移工入口下架時全部不顯示 (FeatureFlags.FOREIGN_WORKERS)
                addOption(badgeView(n.badge), n.nativeTitle, getString(n.descRes),
                        v -> finishWith(UserProfile.foreignWorker(n)));
            }
        }
    }

    private void showLocalTracks() {
        onSubStep = true;
        list.removeAllViews();
        addBack();
        addHeader(getString(R.string.role_pick_worker_local), getString(R.string.track_pick_title));
        for (LocalTrack track : LocalTrack.values()) {
            addOption(iconView(track.iconRes), getString(track.labelRes), getString(track.descRes),
                    v -> finishWith(UserProfile.localWorker(track)));
        }
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

    // ---- 版面元件 (對應 agri.css：.ad-display／.ad-card--interactive／.ad-icon-box／.ad-heading／.ad-caption) ----

    private void addHeader(String title, String subtitle) {
        list.addView(text(title, 28, R.color.ad_text, true));
        TextView s = text(subtitle, 15, R.color.ad_text_2, false);
        s.setPadding(0, dp(6), 0, dp(24));
        list.addView(s);
    }

    /** 上一步：圖示＋文字，整列可點，高度 ≥ 44dp */
    private void addBack() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(0, 0, dp(12), 0);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_left);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(getString(R.string.role_pick_back), 15, R.color.ad_primary_text, true);
        t.setPadding(dp(6), 0, 0, 0);
        row.addView(t);
        row.setBackground(ripple(null));
        row.setOnClickListener(v -> showRoles());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        list.addView(row, lp);
    }

    /** 可點卡片：圖示框｜標題＋說明｜右箭頭 */
    private void addOption(View icon, String title, String desc, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(72));
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color(R.color.ad_surface));
        bg.setStroke(dp(1), color(R.color.ad_border));
        card.setBackground(ripple(bg));
        card.setClickable(true);
        card.setOnClickListener(onClick);

        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        texts.addView(text(title, 16, R.color.ad_text, true));
        TextView d = text(desc, 13, R.color.ad_text_2, false);
        d.setPadding(0, dp(2), 0, 0);
        texts.addView(d);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        card.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        list.addView(card, lp);
    }

    /** .ad-icon-box：40dp、圓角 8、主色淡底，內放 24dp lucide 圖示 */
    private View iconView(int iconRes) {
        FrameLayout box = iconBox();
        ImageView img = new ImageView(this);
        img.setImageResource(iconRes);
        box.addView(img, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        return box;
    }

    /** 外籍工人卡片的語言代碼框 (VI／ID)，與網頁語言切換字樣一致 */
    private View badgeView(String badge) {
        FrameLayout box = iconBox();
        TextView t = text(badge, 15, R.color.ad_primary_text, true);
        box.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return box;
    }

    private FrameLayout iconBox() {
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(color(R.color.ad_primary_soft));
        box.setBackground(bg);
        return box;
    }

    /** 點擊回饋：按下時出現淡色漣漪 */
    private RippleDrawable ripple(GradientDrawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(12));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(color(R.color.ad_surface_2)), content, mask);
    }

    private TextView text(String value, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color(colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
