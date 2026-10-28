package app.focuser.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
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
