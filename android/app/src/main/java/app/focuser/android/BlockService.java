package app.focuser.android;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Watches supported browsers' address bars and sends the user home when a blocked site is open. */
public final class BlockService extends AccessibilityService {
    private static final String TAG = "Focuser";
    private static final long SYNC_EVERY_MS = 5 * 60 * 1000;

    // Package -> address bar view id. ponytail: add a browser here when it is needed.
    private static final Map<String, String> URL_BARS = Map.of(
            "com.android.chrome", "com.android.chrome:id/url_bar",
            "com.brave.browser", "com.brave.browser:id/url_bar",
            "com.microsoft.emmx", "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser:id/location_bar_edit_text");

    // Screens where Focuser can be uninstalled, force-stopped or have its accessibility access removed.
    private static final Set<String> GUARDED = Set.of(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.android.packageinstaller");

    // Apps blocked only on the screens matching the pattern (any visible view id, description or text); an app with
    // no entry is blocked everywhere. Facebook needs no entry: its messages live in Messenger, a separate app that is
    // simply not blocked. ponytail: Instagram's view ids were checked on one release and may change; re-dump them
    // with `adb shell uiautomator dump` when posts, stories or reels stop being blocked.
    // An entry `package#variant` blocks just that part of the app. ponytail: the YouTube ids are a guess from one
    // release; re-dump with `adb shell uiautomator dump` while a Short plays if they stop matching.
    private static final Map<String, Pattern> BLOCKED_SCREENS = Map.of(
            "com.instagram.android", Pattern.compile("row_feed|media_group|reels_tray_container|clips_viewer|clips_media|clips_video|reel_viewer"),
            "com.instagram.android#reels", Pattern.compile("clips_viewer|clips_media|clips_video|reel_viewer"),
            "com.google.android.youtube#shorts", Pattern.compile("reel_recycler|reel_player|shorts_player|reel_watch"));

    private static final long PLACE_EVERY_MS = 60 * 1000;

    private volatile Rules rules;
    /** The phone's last fix; null until there is one, which counts as outside every place. */
    private volatile Location here;
    private Thread syncThread;
    private boolean locating;
    private final LocationListener fix = new LocationListener() {
        @Override
        public void onLocationChanged(Location here) {
            BlockService.this.here = here;
        }
    };

    @Override
    protected void onServiceConnected() {
        load();
        locate();
        syncThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Sync.run(this);
                    load();
                } catch (Exception e) {
                    Log.w(TAG, "sync failed, keeping the last config: " + e.getMessage());
                }
                try {
                    Sync.pushUsage(this);
                } catch (Exception e) {
                    Log.w(TAG, "usage not sent: " + e.getMessage());
                }
                // Location permission may have been granted since the last round.
                locate();
                try {
                    Thread.sleep(SYNC_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        syncThread.start();
    }

    private void load() {
        String json = Sync.prefs(this).getString(Sync.CONFIG_KEY, null);
        if (json == null) return;
        try {
            rules = new Rules(json);
            Sync.migrateApps(this, rules);
        } catch (Exception e) {
            Log.w(TAG, "stored config unreadable: " + e.getMessage());
        }
    }

    /** Follows the phone's position (GPS and network) once the user has allowed location. */
    private void locate() {
        if (locating || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager manager = getSystemService(LocationManager.class);
        for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!manager.isProviderEnabled(provider)) continue;
            manager.requestLocationUpdates(provider, PLACE_EVERY_MS, 0, fix, Looper.getMainLooper());
            Location last = manager.getLastKnownLocation(provider);
            if (last != null && here == null) here = last;
            locating = true;
        }
    }

    /** The package of every app entry blocked right now: those chosen for the lists that are on at this place. */
    private Set<String> blockedEntries(Rules current) {
        Set<String> entries = new HashSet<>();
        for (String id : current.activeListIds(LocalDateTime.now(), here)) entries.addAll(Sync.apps(this, id));
        return entries;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Rules current = rules;
        CharSequence pkg = event.getPackageName();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (current == null || pkg == null || root == null) return;
        if (GUARDED.contains(pkg.toString())) {
            // ponytail: matches the app's label anywhere on screen, so it also fires on the Settings app list. Narrow it if that annoys.
            if (current.protectedNow(LocalDateTime.now()) && !root.findAccessibilityNodeInfosByText("Focuser").isEmpty()) {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
            return;
        }
        String name = pkg.toString();
        boolean chosen = false;
        for (String entry : blockedEntries(current)) {
            if (!entry.equals(name) && !entry.startsWith(name + "#")) continue;
            chosen = true;
            Pattern blocked = BLOCKED_SCREENS.get(entry);
            if (blocked == null || shows(root, blocked, 0)) {
                // Landing on the feed would leave no way into the DMs, so send the user to the DM tab if there is one.
                List<AccessibilityNodeInfo> dm = blocked == null
                        ? List.of() : root.findAccessibilityNodeInfosByViewId(name + ":id/direct_tab");
                boolean clicked = !dm.isEmpty() && click(dm.get(0));
                if (!clicked) performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
        }
        if (chosen) return;
        String barId = URL_BARS.get(pkg.toString());
        if (barId == null) return;
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(barId);
        if (bars.isEmpty() || bars.get(0).getText() == null) return;
        if (current.blocks(bars.get(0).getText().toString(), LocalDateTime.now(), here)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private static boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private static boolean shows(AccessibilityNodeInfo node, Pattern pattern, int depth) {
        if (node == null || depth > 40) return false;
        for (CharSequence label : new CharSequence[] {
                node.getViewIdResourceName(), node.getContentDescription(), node.getText()}) {
            if (label != null && node.isVisibleToUser() && pattern.matcher(label).find()) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (shows(node.getChild(i), pattern, depth + 1)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (syncThread != null) syncThread.interrupt();
        getSystemService(LocationManager.class).removeUpdates(fix);
        super.onDestroy();
    }
}
