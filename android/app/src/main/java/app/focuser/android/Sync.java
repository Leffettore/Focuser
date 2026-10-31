package app.focuser.android;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pulls the config document from the personal server and keeps the last good copy. */
final class Sync {
    static final String PREFS = "focuser";
    static final String URL_KEY = "sync_url";
    static final String TOKEN_KEY = "sync_token";
    /** The apps blocked on this device before they were chosen per list; copied to every list once. */
    static final String APPS_KEY = "apps";
    static final String CONFIG_KEY = "config";

    private Sync() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Entries blocked on this device for one list, never part of the synced document: a package name, or
     * {@code package#variant} for part of an app (Instagram's reels, YouTube's shorts).
     */
    static Set<String> apps(Context context, String listId) {
        return prefs(context).getStringSet(APPS_KEY + ":" + listId, Set.of());
    }

    static void setApps(Context context, String listId, Set<String> entries) {
        prefs(context).edit().putStringSet(APPS_KEY + ":" + listId, new HashSet<>(entries)).apply();
    }

    /** Moves the old device-wide app choice onto every list the first time lists are known. */
    static void migrateApps(Context context, Rules rules) {
        Set<String> old = prefs(context).getStringSet(APPS_KEY, null);
        if (old == null) return;
        for (String id : rules.listIds()) setApps(context, id, old);
        prefs(context).edit().remove(APPS_KEY).apply();
    }

    /** The stored config, or null before the first sync. */
    static Rules rules(Context context) {
        String json = prefs(context).getString(CONFIG_KEY, null);
        try {
            return json == null ? null : new Rules(json);
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean hasUsageAccess(Context context) {
        android.app.AppOpsManager ops = context.getSystemService(android.app.AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.getPackageName())
                == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /** Sends today's and yesterday's foreground time per app to the server, which lines the devices up. Needs usage access. */
    static void pushUsage(Context context) throws IOException, JSONException {
        SharedPreferences prefs = prefs(context);
        String url = prefs.getString(URL_KEY, "").replaceAll("config\\.json$", "") + "command";
        // Listed on the server's device tabs even before it has usage to send.
        Server.fetch(context, url, new JSONObject().put("cmd", "report_device")
                .put("args", new JSONObject().put("device", Rules.DEVICE)).toString(), false);
        if (!hasUsageAccess(context)) return;
        ZoneId zone = ZoneId.systemDefault();
        long since = LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        List<UsageStats> days = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, System.currentTimeMillis());
        PackageManager pm = context.getPackageManager();
        JSONArray rows = new JSONArray();
        for (UsageStats day : days) {
            long seconds = day.getTotalTimeInForeground() / 1000;
            if (seconds < 60) continue;
            String label = day.getPackageName();
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(label, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled since: the package name will do
            }
            rows.put(new JSONObject().put("device", Rules.DEVICE)
                    .put("date", dateOf(day.getFirstTimeStamp(), zone))
                    .put("target", day.getPackageName()).put("label", label).put("seconds", seconds));
        }
        if (rows.length() == 0) return;
        String body = new JSONObject().put("cmd", "report_usage").put("args", new JSONObject().put("rows", rows)).toString();
        Server.fetch(context, url, body, false);
    }

    private static String dateOf(long millis, ZoneId zone) {
        return java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString();
    }

    /** Fetches, checks the body parses as a config, then stores it. Throws on any failure so the old copy stays. */
    static void run(Context context) throws IOException, JSONException {
        SharedPreferences prefs = prefs(context);
        String url = prefs.getString(URL_KEY, "");
        if (url.isEmpty()) throw new IOException("no sync URL set");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String token = prefs.getString(TOKEN_KEY, "");
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            String body = out.toString(StandardCharsets.UTF_8);
            new Rules(body); // reject garbage before it replaces a good copy
            prefs.edit().putString(CONFIG_KEY, body).apply();
        } finally {
            conn.disconnect();
        }
    }
}
