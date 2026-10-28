package app.focuser.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One screen: sync URL, token, a sync-now button, and a shortcut to the accessibility settings. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(48, 48, 48, 48);

        EditText url = field("http://<tailscale-ip>:<port>/config.json", Sync.URL_KEY, false);
        EditText token = field("Bearer token (optional)", Sync.TOKEN_KEY, true);
        TextView status = new TextView(this);

        Button syncNow = new Button(this);
        syncNow.setText("Save and sync now");
        syncNow.setOnClickListener(v -> {
            Sync.prefs(this).edit()
                    .putString(Sync.URL_KEY, url.getText().toString().trim())
                    .putString(Sync.TOKEN_KEY, token.getText().toString().trim())
                    .apply();
            status.setText("Syncing…");
            new Thread(() -> {
                String result;
                try {
                    Sync.run(this);
                    result = "Synced.";
                } catch (Exception e) {
                    result = "Sync failed: " + e.getMessage();
                }
                String shown = result;
                runOnUiThread(() -> status.setText(shown));
            }).start();
        });

        Button access = new Button(this);
        access.setText("Turn on the Focuser accessibility service");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        for (android.view.View v : new android.view.View[] {url, token, syncNow, access, status}) column.addView(v);
        setContentView(column);
    }

    private EditText field(String hint, String key, boolean secret) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine();
        edit.setText(Sync.prefs(this).getString(key, ""));
        if (secret) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }
}
