package app.focuser.android;

import android.Manifest;
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
import android.location.Location;
import android.location.LocationManager;
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
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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

    // Parts of an app that can be blocked on their own; the key is stored in the list's app set.
    private static final java.util.Map<String, String> VARIANTS = new java.util.LinkedHashMap<>();
    static {
        VARIANTS.put("com.instagram.android#dms", "Instagram – everything but DMs");
        VARIANTS.put("com.instagram.android#reels", "Instagram – Reels only");
        VARIANTS.put("com.google.android.youtube#shorts", "YouTube – Shorts only");
    }

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
        public String blockedApps(String listId) {
            JSONArray out = new JSONArray();
            PackageManager pm = getPackageManager();
            for (String name : Sync.apps(MainActivity.this, listId)) {
                String pkg = name.split("#")[0];
                String label = name;
                String icon = null;
                try {
                    label = VARIANTS.containsKey(name)
                            ? VARIANTS.get(name)
                            : pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                    icon = dataUrl(pm.getApplicationIcon(pkg));
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
        public void removeApp(String listId, String id) {
            if (lockedNow()) return;
            Set<String> chosen = new HashSet<>(Sync.apps(MainActivity.this, listId));
            chosen.remove(id);
            Sync.setApps(MainActivity.this, listId, chosen);
        }

        @JavascriptInterface
        public void pickApps(String listId) {
            runOnUiThread(() -> MainActivity.this.pickApps(listId));
        }

        /** Where the phone last was, as {"lat","lon"}, or "" when unknown; asks for location access on the way. */
        @JavascriptInterface
        public String here() {
            runOnUiThread(MainActivity.this::askLocation);
            Location at = MainActivity.this.lastFix();
            return at == null ? "" : "{\"lat\":" + at.getLatitude() + ",\"lon\":" + at.getLongitude() + "}";
        }

        @JavascriptInterface
        public boolean hasUsageAccess() {
            return Sync.hasUsageAccess(MainActivity.this);
        }

        @JavascriptInterface
        public void openUsageAccess() {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    /** Whether a list that locks itself is inside its hours: nothing that loosens blocking may change then. */
    private boolean lockedNow() {
        Rules rules = Sync.rules(this);
        boolean locked = rules != null && rules.protectedNow(LocalDateTime.now());
        if (locked) Toast.makeText(this, "Locked until the scheduled hours end.", Toast.LENGTH_LONG).show();
        return locked;
    }

    /** Apps are per device and per list: the choice lives in this phone's prefs and follows that list's hours and places. */
    private void pickApps(String listId) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        found.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        found.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        Set<String> before = Sync.apps(this, listId);
        Set<String> chosen = new HashSet<>(before);
        // The parts of an app come first, then every installed app.
        List<String> entries = new ArrayList<>(VARIANTS.keySet());
        List<CharSequence> labels = new ArrayList<>(VARIANTS.values());
        int size = dp(32);
        for (ResolveInfo r : found) {
            // The icon rides in the label as a span: the dialog's own rows already put the checkbox at the start.
            Drawable icon = r.loadIcon(pm);
            icon.setBounds(0, 0, size, size);
            SpannableString label = new SpannableString("  " + r.loadLabel(pm));
            label.setSpan(new CenteredIcon(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            entries.add(r.activityInfo.packageName);
            labels.add(label);
        }
        boolean[] checked = new boolean[entries.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = chosen.contains(entries.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Blocked apps on this device")
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked, (d, which, on) -> {
                    if (on) chosen.add(entries.get(which));
                    else chosen.remove(entries.get(which));
                })
                .setPositiveButton("Save", (d, w) -> {
                    // Adding blocks more; dropping an app would be a way out of a lock.
                    if (!chosen.containsAll(before) && lockedNow()) return;
                    Sync.setApps(this, listId, chosen);
                    web.evaluateJavascript("window.dispatchEvent(new Event('focuser-apps'))", null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Asks for location access, "all the time" last: blocking runs in the background and needs it there too. */
    private void askLocation() {
        String[] needed = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        if (checkSelfPermission(needed[0]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(needed, 1);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
        }
    }

    private Location lastFix() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = getSystemService(LocationManager.class);
        Location here = null;
        for (String provider : manager.getProviders(true)) {
            Location last = manager.getLastKnownLocation(provider);
            if (last != null && (here == null || last.getTime() > here.getTime())) here = last;
        }
        return here;
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
