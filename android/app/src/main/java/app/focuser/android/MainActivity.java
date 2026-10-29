package app.focuser.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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

    // Commands that only read, so the last answer can stand in while the server is unreachable.
    private static final Pattern READS = Pattern.compile(
            "^(list_|get_|app_version$|pomodoro_(status|presets|history)$|allowance_(list|history)$)");

    private LinearLayout strip;
    private TextView offline;
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

        offline = new TextView(this);
        offline.setText("Offline: showing the last saved data. Changes need the server.");
        offline.setTextColor(TEXT);
        offline.setBackgroundColor(Color.parseColor("#4A3A12"));
        offline.setPadding(dp(16), dp(6), dp(16), dp(6));
        offline.setVisibility(View.GONE);
        root.addView(offline);

        web = new WebView(this);
        web.setBackgroundColor(BASE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            /** Page files come through {@link Server}, so the last good copy opens without the tailnet. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!"GET".equals(request.getMethod()) || !url.startsWith(base())) return null;
                try {
                    Server.Reply reply = Server.fetch(MainActivity.this, url, null, true);
                    String mime = reply.type.split(";")[0].trim();
                    String charset = reply.type.contains("charset=") ? reply.type.replaceAll(".*charset=", "") : null;
                    return new WebResourceResponse(mime, charset, reply.status, "OK", null,
                            new ByteArrayInputStream(reply.body));
                } catch (IOException e) {
                    return null;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "FocuserAndroid");
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** The server's address without the document name: where the UI and `/command` live. */
    private String base() {
        return Sync.prefs(this).getString(Sync.URL_KEY, "").replaceAll("config\\.json$", "");
    }

    private void showOffline(boolean on) {
        runOnUiThread(() -> offline.setVisibility(on ? View.VISIBLE : View.GONE));
    }

    /** What the page's Applications screen calls: the phone's own blocked apps, never the server's. */
    private final class Bridge {
        @JavascriptInterface
        public String blockedApps() {
            JSONArray out = new JSONArray();
            PackageManager pm = getPackageManager();
            for (String name : Sync.prefs(MainActivity.this).getStringSet(Sync.APPS_KEY, Set.of())) {
                String label = name;
                String icon = null;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString();
                    icon = dataUrl(pm.getApplicationIcon(name));
                } catch (PackageManager.NameNotFoundException ignored) {
                    // uninstalled since: keep the package name so it can still be removed
                }
                try {
                    out.put(new JSONObject().put("id", name).put("label", label).putOpt("icon", icon));
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out.toString();
        }

        /** Runs a command on the server and answers through `window.__focuserCommand`; status 0 means unreachable. */
        @JavascriptInterface
        public void command(int id, String json) {
            new Thread(() -> {
                int status = 0;
                String body = "";
                try {
                    boolean read = READS.matcher(new JSONObject(json).optString("cmd")).find();
                    Server.Reply reply = Server.fetch(MainActivity.this, base() + "command", json, read);
                    status = reply.status;
                    body = new String(reply.body, StandardCharsets.UTF_8);
                    showOffline(reply.cached);
                } catch (IOException | JSONException e) {
                    showOffline(true);
                }
                String script = "window.__focuserCommand(" + id + "," + status + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(script, null));
            }).start();
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
        CharSequence[] labels = new CharSequence[found.size()];
        boolean[] checked = new boolean[found.size()];
        int size = dp(32);
        for (int i = 0; i < labels.length; i++) {
            // The icon rides in the label as a span: the dialog's own rows already put the checkbox at the start.
            Drawable icon = found.get(i).loadIcon(pm);
            icon.setBounds(0, 0, size, size);
            SpannableString label = new SpannableString("  " + found.get(i).loadLabel(pm));
            label.setSpan(new CenteredIcon(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            labels[i] = label;
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

    /** An icon centred on the text: ImageSpan grows the line only upwards, which leaves the label sitting low. */
    private static final class CenteredIcon extends ImageSpan {
        CenteredIcon(Drawable icon) {
            super(icon);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            Rect bounds = getDrawable().getBounds();
            if (fm != null) {
                Paint.FontMetricsInt font = paint.getFontMetricsInt();
                int middle = (font.ascent + font.descent) / 2;
                fm.ascent = fm.top = Math.min(font.ascent, middle - bounds.height() / 2);
                fm.descent = fm.bottom = Math.max(font.descent, middle + bounds.height() / 2);
            }
            return bounds.right;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                Paint paint) {
            Paint.FontMetricsInt font = paint.getFontMetricsInt();
            canvas.save();
            canvas.translate(x, y + (font.ascent + font.descent) / 2f - getDrawable().getBounds().height() / 2f);
            getDrawable().draw(canvas);
            canvas.restore();
        }
    }

    /** The launcher icon as a PNG the page can show; drawn onto a bitmap since adaptive icons are not bitmaps. */
    private static String dataUrl(Drawable icon) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 96, 96);
        icon.draw(new Canvas(bitmap));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        return "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
