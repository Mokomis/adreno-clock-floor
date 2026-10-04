// SPDX-License-Identifier: GPL-3.0-only
package dev.mokomis.adrenofloor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String HOT_WARNING =
            Floor.HOT_MHZ + " MHz is the GPU's top clock. It ran about 8 °C hotter than 1025 MHz in "
                    + "testing and raised the system thermal status to severe within three minutes.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView status;
    private TextView details;
    private TextView hot;
    private ProgressBar progress;
    private Button stock;
    private final Button[] floorButtons = new Button[Floor.CHOICES.length];
    private Button refresh;
    private Switch boot;
    private Switch watch;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Floor.prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(247, 249, 252));

        TextView title = text("GPU Clock Floor", 30, Color.rgb(20, 25, 34));
        root.addView(title);

        TextView subtitle = text(
                "Root utility for Adreno tablets. Holds the GPU clock up between frames. "
                        + "Takes effect at once and resets on reboot unless you choose otherwise below.",
                15, Color.rgb(83, 91, 105));
        subtitle.setPadding(0, dp(6), 0, dp(24));
        root.addView(subtitle);

        status = text("Checking…", 22, Color.rgb(40, 48, 60));
        root.addView(status);

        details = text("", 14, Color.rgb(90, 98, 112));
        details.setPadding(0, dp(8), 0, dp(8));
        root.addView(details);

        hot = text("Warning: " + HOT_WARNING, 14, Color.rgb(175, 48, 48));
        hot.setPadding(0, 0, 0, dp(14));
        hot.setVisibility(View.GONE);
        root.addView(hot);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(38), dp(38));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.setMargins(0, 0, 0, dp(18));
        root.addView(progress, pp);

        for (int i = 0; i < Floor.CHOICES.length; i++) {
            final int mhz = Floor.CHOICES[i];
            floorButtons[i] = button("Floor " + mhz + " MHz");
            floorButtons[i].setOnClickListener(v -> choose(mhz));
            root.addView(floorButtons[i]);
        }
        stock = button("Restore stock");
        refresh = button("Refresh status");
        root.addView(stock);
        root.addView(refresh);

        watch = toggle("Only while streaming",
                "Holds the floor while Punktfunk is in front and restores stock when it is not.");
        root.addView(watch);
        boot = toggle("Apply at startup",
                "After a reboot, puts the floor back. With the option above, it restarts the watcher instead.");
        root.addView(boot);

        TextView warning = text(
                "A floor also turns inter-frame power collapse off; restoring stock turns it back on "
                        + "and switches both options off. Expect more heat and battery drain while a "
                        + "floor is set, in every app. The app refuses a floor the GPU's frequency "
                        + "table does not list.",
                13, Color.rgb(105, 78, 50));
        warning.setPadding(0, dp(24), 0, 0);
        root.addView(warning);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        stock.setOnClickListener(v -> restoreStock());
        refresh.setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The system may have stopped the watcher with the app. The switch is the truth: start it again.
        if (prefs.getBoolean(Floor.KEY_WATCH, false)) WatchService.start(this);
        refresh();
    }

    /** The top clock always asks first, however it will come to be applied. */
    private void choose(int mhz) {
        if (mhz < Floor.HOT_MHZ) {
            select(mhz);
            return;
        }
        String how = prefs.getBoolean(Floor.KEY_WATCH, false) ? " It will apply whenever Punktfunk is in front."
                : prefs.getBoolean(Floor.KEY_BOOT, false) ? " It will also apply after every reboot." : "";
        new AlertDialog.Builder(this)
                .setTitle("Set " + mhz + " MHz?")
                .setMessage(HOT_WARNING + how)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> select(mhz))
                .show();
    }

    private void select(int mhz) {
        prefs.edit().putInt(Floor.KEY_MHZ, mhz).apply();
        if (prefs.getBoolean(Floor.KEY_WATCH, false)) {
            // The watcher owns the driver: it applies the new floor when streaming is in front.
            WatchService.start(this);
            refresh();
        } else {
            run(Floor.changeScript(mhz));
        }
    }

    private void restoreStock() {
        prefs.edit().putBoolean(Floor.KEY_WATCH, false).putBoolean(Floor.KEY_BOOT, false).apply();
        WatchService.stop(this);
        run(Floor.changeScript(Floor.STOCK));
    }

    private void setWatch(boolean on) {
        if (!on) {
            prefs.edit().putBoolean(Floor.KEY_WATCH, false).apply();
            WatchService.stop(this);
            refresh();
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            Floor.Result granted = Floor.runRoot(Floor.grantWatchScript(getPackageName()));
            runOnUiThread(() -> {
                if (granted.ok()) {
                    prefs.edit().putBoolean(Floor.KEY_WATCH, true).apply();
                    WatchService.start(this);
                } else {
                    details.setText("Couldn't get permission to see which app is in front.\n" + granted.output);
                }
                refresh();
            });
        });
    }

    private void refresh() {
        run(Floor.inspectScript());
    }

    private void run(String script) {
        setBusy(true);
        executor.execute(() -> {
            Floor.Result result = Floor.runRoot(script);
            runOnUiThread(() -> {
                setBusy(false);
                showResult(result);
            });
        });
    }

    private void showResult(Floor.Result result) {
        int selected = prefs.getInt(Floor.KEY_MHZ, Floor.DEFAULT_MHZ);
        boolean watching = prefs.getBoolean(Floor.KEY_WATCH, false);
        setSwitch(watch, watching, (b, on) -> setWatch(on));
        setSwitch(boot, prefs.getBoolean(Floor.KEY_BOOT, false),
                (b, on) -> prefs.edit().putBoolean(Floor.KEY_BOOT, on).apply());
        hot.setVisibility(selected >= Floor.HOT_MHZ ? View.VISIBLE : View.GONE);

        String line = result.lastTaggedLine();
        if (!result.ok()) {
            status.setText("Unable to read or change the GPU clock floor");
            status.setTextColor(Color.rgb(175, 48, 48));
            details.setText(line != null && line.startsWith("ERROR|")
                    ? line.substring(6) : "Check root permission and device compatibility.\n" + result.output);
            stock.setEnabled(false);
            for (Button b : floorButtons) b.setEnabled(false);
            return;
        }
        String[] parts = line.split("\\|", -1);
        int floor = parseInt(parts.length > 1 ? parts[1] : "", Floor.STOCK);
        String ifpc = parts.length > 2 ? parts[2].trim() : "?";
        int milliC = parseInt(parts.length > 3 ? parts[3] : "", -1);
        String table = " " + (parts.length > 4 ? parts[4].trim() : "") + " ";

        boolean on = floor > 0;
        status.setText(on ? "Floor " + floor + " MHz" : "Stock: no clock floor");
        status.setTextColor(on ? Color.rgb(22, 128, 82) : Color.rgb(48, 78, 124));
        details.setText("Inter-frame power collapse: " + ("1".equals(ifpc) ? "on" : "0".equals(ifpc) ? "off" : ifpc)
                + (milliC >= 0 ? "\nGPU temperature: " + (milliC / 1000) + " °C" : "")
                + (watching ? "\nWhile streaming: " + selected + " MHz" : ""));

        stock.setEnabled(on || !"1".equals(ifpc) || watching || boot.isChecked());
        for (int i = 0; i < Floor.CHOICES.length; i++) {
            int mhz = Floor.CHOICES[i];
            boolean current = watching ? mhz == selected : mhz == floor;
            floorButtons[i].setEnabled(!current && table.contains(" " + mhz + " "));
        }
    }

    /** Sets a switch without firing its listener. */
    private static void setSwitch(Switch view, boolean on, CompoundButton.OnCheckedChangeListener listener) {
        view.setOnCheckedChangeListener(null);
        view.setChecked(on);
        view.setOnCheckedChangeListener(listener);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? ProgressBar.VISIBLE : ProgressBar.GONE);
        if (busy) {
            stock.setEnabled(false);
            for (Button b : floorButtons) b.setEnabled(false);
        }
        refresh.setEnabled(!busy);
        watch.setEnabled(!busy);
        boot.setEnabled(!busy);
    }

    private Switch toggle(String label, String summary) {
        Switch view = new Switch(this);
        view.setText(label + "\n" + summary);
        view.setTextSize(15);
        view.setTextColor(Color.rgb(40, 48, 60));
        view.setPadding(0, dp(14), 0, dp(6));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
