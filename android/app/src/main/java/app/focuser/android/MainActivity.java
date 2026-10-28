package app.focuser.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The desktop UI, served by the personal server and shown in a WebView. Editing there changes the
 * server's lists; the service pulls them on its next sync. The strip on top only appears when
 * something native is missing: the server address, or the accessibility service that enforces.
 */
public class MainActivity extends Activity {
    private static final int BASE = Color.parseColor("#0B0B11");
    private static final int TEXT = Color.parseColor("#E8E8F0");

    private LinearLayout strip;
    private TextView message;
    private Button action;
    private EditText address;
    private WebView web;
    private String loaded = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BASE);
        // Targeting Android 15 draws under the system bars, so keep the content clear of them.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setPadding(dp(16), dp(10), dp(16), dp(10));
        message = new TextView(this);
        message.setTextColor(TEXT);
        address = new EditText(this);
        address.setHint("http://100.x.x.x:8780/config.json");
        address.setHintTextColor(Color.GRAY);
        address.setTextColor(TEXT);
        address.setSingleLine();
        address.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        action = new Button(this);
        strip.addView(message);
        strip.addView(address);
        strip.addView(action);
        root.addView(strip);

        web = new WebView(this);
        web.setBackgroundColor(BASE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "FocuserAndroid");
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** What the page's Applications screen calls: the phone's own blocked apps, never the server's. */
    private final class Bridge {
        @JavascriptInterface
        public String blockedApps() {
            JSONArray out = new JSONArray();
            PackageManager pm = getPackageManager();
            for (String name : Sync.prefs(MainActivity.this).getStringSet(Sync.APPS_KEY, Set.of())) {
                String label = name;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                    // uninstalled since: keep the package name so it can still be removed
                }
                try {
                    out.put(new JSONObject().put("id", name).put("label", label));
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public void removeApp(String id) {
            Set<String> chosen = new HashSet<>(Sync.prefs(MainActivity.this).getStringSet(Sync.APPS_KEY, Set.of()));
            chosen.remove(id);
            Sync.prefs(MainActivity.this).edit().putStringSet(Sync.APPS_KEY, chosen).apply();
        }

        @JavascriptInterface
        public void pickApps() {
            runOnUiThread(MainActivity.this::pickApps);
        }
    }

    /** Apps are per device: the choice lives in this phone's prefs and follows the synced lists' schedules. */
    private void pickApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        found.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        found.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        Set<String> chosen = new HashSet<>(Sync.prefs(this).getStringSet(Sync.APPS_KEY, Set.of()));
        String[] labels = new String[found.size()];
        boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = found.get(i).loadLabel(pm).toString();
            checked[i] = chosen.contains(found.get(i).activityInfo.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("Blocked apps on this device")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> {
                    String name = found.get(which).activityInfo.packageName;
                    if (on) chosen.add(name);
                    else chosen.remove(name);
                })
                .setPositiveButton("Save", (d, w) -> {
                    Sync.prefs(this).edit().putStringSet(Sync.APPS_KEY, chosen).apply();
                    web.evaluateJavascript("window.dispatchEvent(new Event('focuser-apps'))", null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String url = Sync.prefs(this).getString(Sync.URL_KEY, "");
        boolean serviceOn = serviceOn();
        strip.setVisibility(url.isEmpty() || !serviceOn ? View.VISIBLE : View.GONE);
        address.setVisibility(url.isEmpty() ? View.VISIBLE : View.GONE);
        if (url.isEmpty()) {
            message.setText("Enter the address of your Focuser server.");
            action.setText("Save and connect");
            action.setOnClickListener(v -> {
                String typed = address.getText().toString().trim();
                if (typed.isEmpty()) return;
                Sync.prefs(this).edit().putString(Sync.URL_KEY, typed).apply();
                onResume();
            });
        } else {
            message.setText("Blocking is off until the Focuser accessibility service is on.");
            action.setText("Open accessibility settings");
            action.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            // Pull the lists now, so edits made in the UI apply without waiting for the service's timer.
            new Thread(() -> {
                try {
                    Sync.run(this);
                } catch (Exception ignored) {
                    // the service retries every five minutes and keeps the last good copy
                }
            }).start();
            String ui = url.replaceAll("config\\.json$", "");
            if (!ui.equals(loaded)) {
                loaded = ui;
                web.loadUrl(ui);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private boolean serviceOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
