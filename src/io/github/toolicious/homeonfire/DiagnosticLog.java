// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayDeque;

/** Opt-in metadata only. Four UTF-8 JSONL files, each at most 512 KiB. */
final class DiagnosticLog {
    static final String[] FILES = {"events.jsonl", "events.1.jsonl", "events.2.jsonl", "events.3.jsonl"};
    private static final long MAX_BYTES = 512 * 1024;
    private static volatile String error = "";
    private DiagnosticLog() {}

    static synchronized void event(Context ctx, String event, String pkg, String cls, String detail) {
        Prefs prefs = new Prefs(ctx);
        if (prefs.isVerboseLogging()) {
            // Fire OS can filter DEBUG/VERBOSE globally. App verbosity controls
            // detailed metadata; INFO keeps it visible without changing log props.
            Log.i("HomeOnFire", limited(event) + " " + limited(pkg) + "/"
                    + limited(cls) + " " + limited(detail));
        }
        if (!prefs.isDiagnosticLogging()) return;
        try {
            File directory = new File(ctx.getFilesDir(), "diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create log directory");
            JSONObject row = new JSONObject();
            row.put("schema", 1);
            row.put("wallTimeMs", System.currentTimeMillis());
            row.put("elapsedTimeMs", SystemClock.elapsedRealtime());
            row.put("backend", new Prefs(ctx).getBackend());
            row.put("event", limited(event));
            row.put("package", limited(pkg));
            row.put("activity", limited(cls));
            row.put("detail", limited(detail));
            byte[] bytes = (row.toString() + "\n").getBytes("UTF-8");
            File active = new File(directory, FILES[0]);
            if (active.length() + bytes.length > MAX_BYTES) {
                File oldest = new File(directory, FILES[3]);
                if (oldest.exists() && !oldest.delete()) throw new IOException("Cannot rotate oldest log");
                for (int i = 2; i >= 0; --i) {
                    File from = new File(directory, FILES[i]);
                    if (from.exists() && !from.renameTo(new File(directory, FILES[i + 1]))) {
                        throw new IOException("Cannot rotate log " + i);
                    }
                }
            }
            try (FileOutputStream stream = new FileOutputStream(active, true)) {
                stream.write(bytes);
            }
            error = "";
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName();
            Log.w("HomeOnFire", "Diagnostic storage unavailable: " + error);
        }
    }

    private static String limited(String value) {
        if (value == null) return "";
        return value.length() > 1400 ? value.substring(0, 1400) : value;
    }

    static String storageError() { return error; }

    static synchronized String tail(Context context) {
        ArrayDeque<String> lines = new ArrayDeque<String>();
        File file = new File(new File(context.getFilesDir(), "diagnostics"), FILES[0]);
        if (!file.exists()) return "No persistent diagnostic events recorded.";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == 200) lines.removeFirst();
                lines.addLast(line);
            }
            StringBuilder text = new StringBuilder();
            for (String item : lines) text.append(item).append('\n');
            return text.toString();
        } catch (IOException ex) {
            return "Cannot read diagnostics: " + ex.getClass().getSimpleName();
        }
    }

    /** A consistent snapshot for adb pull; requires no external-storage permission. */
    static synchronized File export(Context context) throws IOException {
        File output = context.getExternalFilesDir("diagnostics-export");
        if (output == null || (!output.isDirectory() && !output.mkdirs())) {
            throw new IOException("Application-specific external storage is unavailable");
        }
        File directory = new File(context.getFilesDir(), "diagnostics");
        for (String name : FILES) {
            File source = new File(directory, name);
            File destination = new File(output, name);
            if (!source.exists()) {
                if (destination.exists() && !destination.delete()) throw new IOException("Cannot clear old export");
                continue;
            }
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        return output;
    }
}
