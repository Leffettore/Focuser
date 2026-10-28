package app.focuser.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.util.List;
import org.json.JSONException;

/** The dashboard, in the desktop app's order: four stats, the block lists, then sync settings. */
public final class MainActivity extends Activity {
    // Same palette as the desktop app (frontend/src/index.css).
    private static final int BASE = 0xFF0B0B11, SURFACE = 0xFF14141C, ELEVATED = 0xFF1C1C27;
    private static final int FOREGROUND = 0xFFF2F2F7, MUTED = 0xFFA9A9BD, FAINT = 0xFF6E6E88;
    private static final int PRIMARY = 0xFF8B5CF6, WARNING = 0xFFFBBF24, SUCCESS = 0xFF34D399;
    private static final int BORDER = 0x22FFFFFF;

    private LinearLayout content;
    private TextView syncStatus;
    private EditText urlField, tokenField;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BASE);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(content);
        // Targeting Android 15 draws under the system bars, so keep the content clear of them.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        String savedUrl = urlField == null ? Sync.prefs(this).getString(Sync.URL_KEY, "") : urlField.getText().toString();
        String savedToken = tokenField == null ? Sync.prefs(this).getString(Sync.TOKEN_KEY, "") : tokenField.getText().toString();
        content.removeAllViews();

        LinearLayout brand = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = text("Focuser", 16, FOREGROUND, true);
        name.setPadding(dp(10), 0, 0, 0);
        brand.addView(name);
        content.addView(brand);

        TextView title = text("Dashboard", 28, FOREGROUND, true);
        title.setPadding(0, dp(20), 0, 0);
        content.addView(title);
        content.addView(text("Where things stand right now.", 14, FAINT, false));

        Rules rules = loadRules();
        LocalDateTime now = LocalDateTime.now();
        List<Rules.ListInfo> lists = rules == null ? List.of() : rules.lists(now);
        int enabled = 0, ruleCount = 0, locked = 0;
        for (Rules.ListInfo l : lists) {
            if (l.enabled) {
                enabled++;
                ruleCount += l.sites + l.apps;
            }
            if (l.locked) locked++;
        }
        int blocked = Sync.prefs(this).getInt(Sync.blockedKey(), 0);

        LinearLayout top = row();
        top.addView(stat("Blocked today", String.valueOf(blocked), blocked == 0 ? "Nothing turned away yet" : "Attempts stopped", true, null, 0), weight());
        top.addView(stat("Lists enabled", enabled + " of " + lists.size(), null, false, null, lists.isEmpty() ? -1 : enabled * 100 / lists.size()), weight());
        spaced(top);
        LinearLayout bottom = row();
        bottom.addView(stat("Rules in force", String.valueOf(ruleCount), "Sites and apps across enabled lists", false, null, 0), weight());
        bottom.addView(stat("Protected", String.valueOf(locked), locked == 0 ? "No locks running" : "Until the window ends", false, locked > 0 ? WARNING : null, 0), weight());
        spaced(bottom);

        content.addView(section("Block lists", "Set by your server. Change them there."));
        if (lists.isEmpty()) {
            content.addView(card(text("No lists yet. Save your sync URL below and sync.", 14, MUTED, false)));
        }
        for (Rules.ListInfo l : lists) content.addView(listCard(l));

        content.addView(section("Sync", "Pulls your server's config every 5 minutes."));
        urlField = field("http://<tailscale-ip>:<port>/config.json", savedUrl, false);
        tokenField = field("Bearer token (optional)", savedToken, true);
        syncStatus = text(serviceOn() ? "Blocking service is on." : "Blocking service is off. Turn it on below.", 13, serviceOn() ? SUCCESS : WARNING, false);
        syncStatus.setPadding(0, dp(8), 0, dp(8));
        Button sync = button("Save and sync now", true);
        sync.setOnClickListener(v -> syncNow());
        Button access = button("Open accessibility settings", false);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        for (View v : new View[] {urlField, tokenField, syncStatus, sync, access}) content.addView(v);
    }

    private void syncNow() {
        Sync.prefs(this).edit()
                .putString(Sync.URL_KEY, urlField.getText().toString().trim())
                .putString(Sync.TOKEN_KEY, tokenField.getText().toString().trim())
                .apply();
        syncStatus.setText("Syncing…");
        new Thread(() -> {
            String result;
            try {
                Sync.run(this);
                result = null;
            } catch (Exception e) {
                result = "Sync failed: " + e.getMessage();
            }
            String failure = result;
            runOnUiThread(() -> {
                render();
                if (failure != null) {
                    syncStatus.setText(failure);
                    syncStatus.setTextColor(WARNING);
                }
            });
        }).start();
    }

    private Rules loadRules() {
        String json = Sync.prefs(this).getString(Sync.CONFIG_KEY, null);
        try {
            return json == null ? null : new Rules(json);
        } catch (JSONException e) {
            return null;
        }
    }

    private boolean serviceOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/" + BlockService.class.getName());
    }

    // ── views ──

    private View stat(String label, String value, String hint, boolean accent, Integer valueColor, int barPercent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(label, 12, accent ? 0xFFC4B5FD : FAINT, false));
        TextView v = text(value, 26, valueColor == null ? FOREGROUND : valueColor, true);
        v.setPadding(0, dp(6), 0, 0);
        box.addView(v);
        if (hint != null) {
            TextView h = text(hint, 11, FAINT, false);
            h.setPadding(0, dp(2), 0, 0);
            box.addView(h);
        }
        if (barPercent >= 0 && hint == null) {
            LinearLayout bar = new LinearLayout(this);
            bar.setBackground(pill(ELEVATED));
            View fill = new View(this);
            fill.setBackground(pill(PRIMARY));
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(4), Math.max(barPercent, 1)));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(4), 100 - Math.max(barPercent, 1)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(4));
            lp.topMargin = dp(10);
            box.addView(bar, lp);
        }
        View card = card(box);
        card.setBackground(box(accent ? 0xFF241A3D : SURFACE));
        return card;
    }

    private View listCard(Rules.ListInfo l) {
        LinearLayout line = row();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = row();
        nameRow.addView(text(l.name, 14, FOREGROUND, true));
        if (l.scheduled) nameRow.addView(badge("Scheduled", 0xFF60A5FA));
        if (l.locked) nameRow.addView(badge("Protected", WARNING));
        left.addView(nameRow);
        TextView counts = text(l.sites + (l.sites == 1 ? " site" : " sites") + " · " + l.apps + (l.apps == 1 ? " app" : " apps"), 12, FAINT, false);
        counts.setPadding(0, dp(2), 0, 0);
        left.addView(counts);
        line.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(!l.enabled ? "Off" : l.active ? "Blocking now" : "Waiting", 12, l.active ? SUCCESS : FAINT, true));
        View card = card(line);
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(8);
        return card;
    }

    private View section(String title, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(28), 0, dp(4));
        box.addView(text(title, 15, FOREGROUND, true));
        box.addView(text(description, 12, FAINT, false));
        return box;
    }

    private TextView badge(String label, int color) {
        TextView b = text(label, 10, color, true);
        b.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable bg = pill(0x1FFFFFFF & color | 0x22000000);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View card(View inner) {
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(dp(14), dp(14), dp(14), dp(14));
        frame.setBackground(box(SURFACE));
        frame.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        frame.setLayoutParams(lp);
        return frame;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    /** Adds the row to the page with room below it. */
    private void spaced(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        // The stat cards carry a 4dp margin each, which this pulls back so they line up with the text.
        lp.setMargins(-dp(4), dp(10), -dp(4), 0);
        if (row.getParent() == null) content.addView(row, lp);
        else row.setLayoutParams(lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(FAINT);
        e.setTextColor(FOREGROUND);
        e.setTextSize(14);
        e.setSingleLine();
        e.setText(value);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(box(ELEVATED));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : FOREGROUND);
        b.setBackground(primary ? box(PRIMARY) : box(ELEVATED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable box(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), BORDER);
        return d;
    }

    private GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(100));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
