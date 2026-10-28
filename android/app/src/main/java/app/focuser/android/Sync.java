package app.focuser.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;

/** Pulls the config document from the personal server and keeps the last good copy. */
final class Sync {
    static final String PREFS = "focuser";
    static final String URL_KEY = "sync_url";
    static final String TOKEN_KEY = "sync_token";
    static final String CONFIG_KEY = "config";

    private Sync() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String blockedKey() {
        return "blocked_" + java.time.LocalDate.now();
    }

    /** Counts a turned-away page for today's dashboard. Only the service writes it, from one thread. */
    static void countBlock(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putInt(blockedKey(), prefs.getInt(blockedKey(), 0) + 1).apply();
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
