// SPDX-License-Identifier: GPL-3.0-only
package dev.mokomis.adrenofloor;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** The driver nodes, the root scripts that read and write them, and the saved choices. */
final class Floor {
    private static final String KGSL = "/sys/class/kgsl/kgsl-3d0";
    private static final String FLOOR = KGSL + "/gmu/dcvs_tunables/min_freq_mhz";
    private static final String IFPC = KGSL + "/ifpc";
    private static final String TABLE = KGSL + "/freq_table_mhz";
    private static final String TEMP = KGSL + "/temp";

    /** The driver's own value for "no floor". */
    static final int STOCK = -1;
    /** Floors offered, in MHz. Each must appear in the GPU's frequency table to be enabled. */
    static final int[] CHOICES = {1025, 1150, 1225};
    static final int DEFAULT_MHZ = 1025;
    /** At and above this floor the tablet ran hot in testing, so the app asks first. */
    static final int HOT_MHZ = 1225;

    /** Apps that count as streaming for {@link #KEY_WATCH}. */
    static final String[] STREAMING_APPS = {"io.unom.punktfunk", "io.unom.punktfunk.mokomis"};

    private static final String PREFS = "floor";
    /** The floor the options apply, in MHz. */
    static final String KEY_MHZ = "mhz";
    /** Re-apply after a reboot. */
    static final String KEY_BOOT = "boot";
    /** Hold the floor only while a streaming app is in front. */
    static final String KEY_WATCH = "watch";

    private Floor() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isStreamingApp(String pkg) {
        for (String app : STREAMING_APPS) if (app.equals(pkg)) return true;
        return false;
    }

    private static String requireNodes(int code) {
        return "for f in " + FLOOR + " " + IFPC + " " + TABLE + "; do "
                + "[ -e \"$f\" ] || { echo \"ERROR|Unsupported: $f not found\"; exit " + code + "; }; done; ";
    }

    private static String report() {
        return "echo \"STATUS|$(cat " + FLOOR + ")|$(cat " + IFPC + ")|$(cat " + TEMP + " 2>/dev/null)|"
                + "$(cat " + TABLE + ")\"";
    }

    /** Checks the nodes exist, then reports `STATUS|floor|ifpc|temp|table`. */
    static String inspectScript() {
        return requireNodes(20) + report();
    }

    /**
     * Writes the floor and the matching power-collapse value, then reads both back. A floor must
     * be in the frequency table; {@link #STOCK} clears it.
     */
    static String changeScript(int mhz) {
        boolean clear = mhz == STOCK;
        String ifpc = clear ? "1" : "0";
        String inTable = clear ? "" :
                "case \" $(cat " + TABLE + ") \" in *\" " + mhz + " \"*) ;; "
                        + "*) echo 'ERROR|This GPU has no " + mhz + " MHz level'; exit 31;; esac; ";
        return requireNodes(30) + inTable
                + "echo " + mhz + " > " + FLOOR + " || { echo 'ERROR|Could not write the clock floor'; exit 32; }; "
                + "echo " + ifpc + " > " + IFPC + " || { echo 'ERROR|Could not write power collapse'; exit 33; }; "
                + "[ \"$(cat " + FLOOR + ")\" = " + mhz + " ] "
                + "|| { echo 'ERROR|The driver did not keep the clock floor'; exit 34; }; "
                + "[ \"$(cat " + IFPC + ")\" = " + ifpc + " ] "
                + "|| { echo 'ERROR|The driver did not keep the power collapse setting'; exit 35; }; "
                + report();
    }

    /**
     * Lets this app read which app is in front and post its watcher notification, and exempts it
     * from battery optimization and background limits so the system leaves the watcher running.
     */
    static String grantWatchScript(String pkg) {
        return "appops set " + pkg + " GET_USAGE_STATS allow "
                + "|| { echo 'ERROR|Could not grant usage access'; exit 40; }; "
                + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                + "dumpsys deviceidle whitelist +" + pkg + " >/dev/null 2>&1; "
                + "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null; "
                + "appops set " + pkg + " RUN_IN_BACKGROUND allow 2>/dev/null; "
                + "echo 'STATUS|granted'";
    }

    static Result runRoot(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            return new Result(process.waitFor(), output.toString().trim());
        } catch (Throwable error) {
            return new Result(-1, error.toString());
        }
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean ok() {
            String line = lastTaggedLine();
            return exitCode == 0 && line != null && line.startsWith("STATUS|");
        }

        String lastTaggedLine() {
            String found = null;
            for (String line : output.split("\\n")) {
                if (line.startsWith("STATUS|") || line.startsWith("ERROR|")) found = line;
            }
            return found;
        }
    }
}
