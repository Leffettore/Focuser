package app.focuser.android;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads a Focuser `ConfigDocument` and answers "is this address blocked right now?". */
final class Rules {
    private final JSONArray lists;

    Rules(String json) throws JSONException {
        lists = new JSONObject(json).getJSONArray("block_lists");
    }

    boolean blocks(String address, LocalDateTime now) {
        String url = normalize(address);
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list == null || !list.optBoolean("enabled") || !scheduled(list, now)) continue;
            if (matchesWebsite(list.optJSONArray("websites"), url)
                    && !matchesException(list.optJSONArray("exceptions"), url)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a list that locks itself while scheduled is inside its hours, so Focuser itself must stay put. */
    boolean protectedNow(LocalDateTime now) {
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list != null && list.optBoolean("enabled") && !list.isNull("scheduled_protection")
                    && scheduled(list, now)) {
                return true;
            }
        }
        return false;
    }

    /** Lowercase, no scheme, no `www.`/`m.` prefix, so `https://m.YouTube.com/x` becomes `youtube.com/x`. */
    static String normalize(String address) {
        String s = address.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        return s.replaceFirst("^(www|m)\\.", "");
    }

    private static boolean matchesWebsite(JSONArray websites, String url) {
        String host = host(url);
        for (int i = 0; websites != null && i < websites.length(); i++) {
            JSONObject rule = websites.optJSONObject(i);
            if (rule == null || !rule.optBoolean("enabled")) continue;
            // ponytail: only plain domains; keyword and wildcard website rules are ignored. Add them if a list needs them.
            String domain = rule.optJSONObject("match_type") == null
                    ? null
                    : rule.optJSONObject("match_type").optString("Domain", null);
            if (domain != null && (host.equals(domain) || host.endsWith("." + domain))) return true;
        }
        return false;
    }

    private static boolean matchesException(JSONArray exceptions, String url) {
        for (int i = 0; exceptions != null && i < exceptions.length(); i++) {
            JSONObject rule = exceptions.optJSONObject(i);
            if (rule == null || !rule.optBoolean("enabled")) continue;
            JSONObject type = rule.optJSONObject("exception_type");
            if (type == null) continue; // `LocalFiles` is a bare string and means nothing on a phone.
            if (type.has("Domain") && host(url).endsWith(type.optString("Domain"))) return true;
            if (type.has("UrlPath") && url.startsWith(type.optString("UrlPath").toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (type.has("Wildcard") && wildcard(type.optString("Wildcard")).matcher(url).matches()) return true;
        }
        return false;
    }

    private static Pattern wildcard(String glob) {
        StringBuilder re = new StringBuilder();
        for (String part : glob.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (re.length() > 0) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }

    private static String host(String url) {
        int end = url.length();
        for (char c : new char[] {'/', '?', '#', ':'}) {
            int at = url.indexOf(c);
            if (at >= 0 && at < end) end = at;
        }
        return url.substring(0, end);
    }

    /** A list with no schedule is always on; otherwise `now` must fall in a slot (slots may wrap past midnight). */
    private static boolean scheduled(JSONObject list, LocalDateTime now) {
        JSONObject schedule = list.optJSONObject("schedule");
        JSONArray slots = schedule == null ? null : schedule.optJSONArray("time_slots");
        if (schedule == null || !schedule.optBoolean("enabled") || slots == null || slots.length() == 0) return true;
        LocalTime t = now.toLocalTime();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            if (slot == null) continue;
            DayOfWeek day = day(slot.optString("day"));
            LocalTime start = LocalTime.parse(slot.optString("start"));
            LocalTime end = LocalTime.parse(slot.optString("end"));
            boolean wraps = !end.isAfter(start);
            if (day == now.getDayOfWeek() && !t.isBefore(start) && (wraps || t.isBefore(end))) return true;
            if (wraps && day == now.getDayOfWeek().minus(1) && t.isBefore(end)) return true;
        }
        return false;
    }

    private static DayOfWeek day(String name) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().regionMatches(true, 0, name, 0, 3)) return d;
        }
        throw new IllegalArgumentException("unknown day " + name);
    }
}
