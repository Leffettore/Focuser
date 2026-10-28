package app.focuser.android;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    // Apps that stay open on the screens matching the pattern (any view id, description or text) and go home
    // everywhere else. Facebook needs no entry: its messages live in Messenger, a separate app that is simply
    // not blocked. ponytail: Instagram's view ids are unverified and change between releases; tune them with
    // `adb logcat -s Focuser` after a block, which logs what was on screen.
    private static final Map<String, Pattern> ALLOWED_SCREENS = Map.of(
            "com.instagram.android", Pattern.compile("direct_thread|thread_composer|direct_inbox|inbox_refreshable"));

    private volatile Rules rules;
    private Thread syncThread;

    @Override
    protected void onServiceConnected() {
        load();
        syncThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Sync.run(this);
                    load();
                } catch (Exception e) {
                    Log.w(TAG, "sync failed, keeping the last config: " + e.getMessage());
                }
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
        } catch (Exception e) {
            Log.w(TAG, "stored config unreadable: " + e.getMessage());
        }
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
        if (Sync.prefs(this).getStringSet(Sync.APPS_KEY, Set.of()).contains(name)
                && current.anyActive(LocalDateTime.now())) {
            Pattern allowed = ALLOWED_SCREENS.get(name);
            if (allowed == null || !shows(root, allowed, 0)) performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        String barId = URL_BARS.get(pkg.toString());
        if (barId == null) return;
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(barId);
        if (bars.isEmpty() || bars.get(0).getText() == null) return;
        if (current.blocks(bars.get(0).getText().toString(), LocalDateTime.now())) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private static boolean shows(AccessibilityNodeInfo node, Pattern pattern, int depth) {
        if (node == null || depth > 40) return false;
        for (CharSequence label : new CharSequence[] {
                node.getViewIdResourceName(), node.getContentDescription(), node.getText()}) {
            if (label != null && pattern.matcher(label).find()) return true;
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
        super.onDestroy();
    }
}
