package app.focuser.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Talks to the personal server and keeps its last good answers, so the UI still opens when the
 * tailnet is down. Only answers the caller marks cacheable (page files and read commands) are kept.
 */
final class Server {
    /** After a failed request, go straight to the cache for this long instead of waiting out another timeout. */
    private static final long BACKOFF_MS = 20_000;
    private static volatile long downUntil;

    static final class Reply {
        final int status;
        final String type;
        final byte[] body;
        final boolean cached;

        Reply(int status, String type, byte[] body, boolean cached) {
            this.status = status;
            this.type = type;
            this.body = body;
            this.cached = cached;
        }
    }

    private Server() {}

    /** GET when {@code body} is null, otherwise POST it as JSON. Throws only if the server is down and nothing is cached. */
    static Reply fetch(Context context, String url, String body, boolean cacheable) throws IOException {
        File file = new File(context.getFilesDir(), "server-" + hash(url + "\n" + body));
        if (cacheable && System.currentTimeMillis() < downUntil && file.exists()) return read(file);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(10_000);
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            try {
                int status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String type = conn.getContentType() == null ? "text/plain" : conn.getContentType();
                Reply reply = new Reply(status, type, in == null ? new byte[0] : readAll(in), false);
                if (cacheable && status == 200) write(file, reply);
                return reply;
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            downUntil = System.currentTimeMillis() + BACKOFF_MS;
            if (cacheable && file.exists()) return read(file);
            throw e;
        }
    }

    /** Stored as the content type, a newline, then the body. */
    private static void write(File file, Reply reply) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((reply.type + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(reply.body);
        Files.write(file.toPath(), out.toByteArray());
    }

    private static Reply read(File file) throws IOException {
        byte[] all = Files.readAllBytes(file.toPath());
        int split = 0;
        while (split < all.length && all[split] != '\n') split++;
        String type = new String(all, 0, split, StandardCharsets.UTF_8);
        return new Reply(200, type, Arrays.copyOfRange(all, Math.min(split + 1, all.length), all.length), true);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String hash(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
