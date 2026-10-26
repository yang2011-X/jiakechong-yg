package com.didjdk.adbhelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the bundled adb server connected to the last known device.
 * The app's adb server drops TCP connections to wireless devices after a while
 * and the UI then keeps using a stale "-t <id>" selector, which makes every
 * shell based screen (file manager / app manager / process list) render empty.
 * This watchdog re-issues "adb connect <host>:<port>" so those screens keep working.
 */
public class RcWatch {
    private static final String TAG = "BEETLE_RC";
    private static final Pattern DEV = Pattern.compile("([\\w.\\-]+:\\d+)\\s+device\\b");
    private static boolean started = false;
    private static final Object LOCK = new Object();
    private static Context sctx;


    /** Simple log bridge used by patched smali probes. */
    public static void log(String s) {
        Log.e(TAG, String.valueOf(s));
    }

    /** Two-part log bridge: tags each probe chunk so it can be grepped. */
    public static void log2(String tag, String s) {
        Log.e(TAG, tag + " | " + s);
        if (s != null) {
            int i = 0;
            while (i < 6 && s.indexOf("====loading") > 0) { break; }
        }
    }
    public static void start(final Context ctx) {
        synchronized (LOCK) {
            if (started) return;
            started = true;
        }
        sctx = ctx.getApplicationContext();
        try {
            String h = ctx.getSharedPreferences("beetle_rc", 0).getString("host", null);
            File adbf = new File(ctx.getFilesDir(), "adb");
            if (h != null && adbf.exists()) {
                installWrapper(ctx, h);
            }
        } catch (Throwable ignored) { }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop(ctx.getApplicationContext());
                } catch (Throwable e) {
                    Log.e(TAG, "fatal " + e);
                }
            }
        }, "beetle-rc");
        t.setDaemon(true);
        t.start();
        Log.e(TAG, "watchdog started");
    }


    /**
     * Blocking variant used by patched smali: called right before a screen sends its
     * first adb command, so the transport id the app cached still resolves.
     */
    public static void ensureConnected() {
        Context ctx = sctx;
        if (ctx == null) {
            return;
        }
        try {
            String adbPath = new File(ctx.getFilesDir(), "adb").getAbsolutePath();
            String home = ctx.getFilesDir().getAbsolutePath();
            SharedPreferences sp = ctx.getSharedPreferences("beetle_rc", 0);
            String host = sp.getString("host", null);
            if (host == null) {
                return;
            }
            if (!serverListening()) {
                return;
            }
            String out = adb(adbPath, home, "devices");
            if (DEV.matcher(out).lookingAt() && out.contains(host)) {
                return;
            }
            for (int i = 0; i < 2; i++) {
                Log.e(TAG, "ensure " + host + " -> " + adb(adbPath, home, "connect " + host).trim());
                Thread.sleep(600);
                String chk = adb(adbPath, home, "devices");
                if (chk.contains(host) && chk.contains("device")) {
                    return;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "ensure failed " + e);
        }
    }

    private static final String WRAP_MARK = "# BEETLE_WRAP";

    /**
     * Replaces files/adb with a thin shell wrapper that re-issues "adb connect"
     * before every non-connect invocation. The app starts its own adb server per
     * session, so the transport is gone by the time a screen sends its command;
     * connecting inside the same process that talks to the server is race free.
     */
    private static void installWrapper(Context ctx, String host) {
        try {
            File dir = ctx.getFilesDir();
            File real = new File(dir, "adb-real");
            File cur = new File(dir, "adb");
            if (!real.exists() && cur.exists()) {
                if (!cur.renameTo(real)) {
                    Log.e(TAG, "adb rename failed");
                    return;
                }
            }
            if (!real.exists()) {
                return;
            }
            String body = "#!/system/bin/sh\n" + WRAP_MARK + "\n"
                    + "D=\"$(dirname \"$0\")\"\n"
                    + "export ANDROID_ADB_SERVER_PORT=5038\n"
                    + "export ADB_SERVER_SOCKET=tcp:127.0.0.1:5038\n"
                    + "case \"$*\" in\n"
                    + "  *start-server*|*kill-server*|*fork-server*|*--reply-fd*) exec \"$D/adb-real\" \"$@\" ;;\n"
                    + "esac\n"
                    + "\"$D/adb-real\" -P 5038 start-server >/dev/null 2>&1\n"
                    + "\"$D/adb-real\" -P 5038 connect " + host + " >/dev/null 2>&1\n"
                    + "exec \"$D/adb-real\" \"$@\"\n";
            FileOutputStream fos = new FileOutputStream(cur);
            fos.write(body.getBytes("UTF-8"));
            fos.close();
            cur.setExecutable(true, false);
            cur.setReadable(true, false);
            Log.e(TAG, "wrapper installed for " + host);
        } catch (Throwable e) {
            Log.e(TAG, "wrapper install failed " + e);
        }
    }

    private static boolean isWrapper(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[64];
            int n = in.read(b);
            in.close();
            return n > 0 && new String(b, 0, n, "UTF-8").contains(WRAP_MARK);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Command prefix handed to every shell screen. Re-connects first (the app's
     * adb server is restarted per session, dropping wireless transports) and gives
     * the daemon a moment to settle, then runs adb without a stale "-t <id>".
     */
    public static String prefix() {
        Context ctx = sctx;
        String host = null;
        try {
            if (ctx != null) {
                host = ctx.getSharedPreferences("beetle_rc", 0).getString("host", null);
            }
        } catch (Throwable ignored) {
        }
        if (host == null) {
            return "adb ";
        }
        return "adb connect " + host + " >/dev/null 2>&1; sleep 0.4; adb ";
    }
    private static boolean serverListening() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5038), 300);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String adb(String adbPath, String home, String args) {
        Process p = null;
        try {
            String cmd = "HOME=" + home + " TMPDIR=" + home + " " + adbPath + " " + args + " 2>&1";
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 65536) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable e) {
            return "ERR " + e;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Copies the bundled com.beetle.AppDump helper out of assets on first use. */
    private static String localHelper(Context ctx) {
        File f = new File(ctx.getFilesDir(), "beetle_server.apk");
        if (f.length() > 0) {
            return f.getAbsolutePath();
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = ctx.getAssets().open("beetle_server.apk");
            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            Log.e(TAG, "helper extracted " + f.length());
            return f.getAbsolutePath();
        } catch (Throwable e) {
            Log.e(TAG, "helper extract failed " + e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** Makes sure /data/local/tmp/beetle_server.apk exists on the connected device. */
    private static boolean ensureHelper(String adbPath, String home, String local) {
        try {
            String out = adb(adbPath, home, "shell ls /data/local/tmp/beetle_server.apk");
            if (out.contains("beetle_server.apk")) {
                return true;
            }
            String push = adb(adbPath, home, "push " + local + " /data/local/tmp/beetle_server.apk");
            Log.e(TAG, "helper push -> " + push.trim());
            return push.contains("1 file pushed") || push.contains("0 skipped");
        } catch (Throwable e) {
            Log.e(TAG, "helper push failed " + e);
            return false;
        }
    }

    /**
     * The app pulls app icons one by one through its own shell session, but that
     * session is killed as soon as the loading marker arrives, so only the first
     * few icons ever arrive. Pull the whole icon directory in one go instead and
     * drop it where the app looks for it (files/icons/&lt;serial&gt;/&lt;package&gt;.png).
     */
    /** Walks down to the deepest directory (files/icons/&lt;serial&gt;/&lt;serial2&gt;). */
    private static File deepest(File root) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    File d = deepest(k);
                    if (d != null) {
                        return d;
                    }
                }
            }
        }
        return root;
    }

    private static boolean syncIcons(Context ctx, String adbPath, String home, String host) {
        try {
            File iconsRoot = new File(ctx.getFilesDir(), "icons");
            File target = deepest(iconsRoot);
            if (target == null) {
                return false;
            }
            String out = adb(adbPath, home, "-s " + host + " pull /data/local/tmp/icons/ "
                    + target.getAbsolutePath() + "/");
            Log.e(TAG, "icons sync -> " + out.trim());
            return out.contains("file pulled") || out.contains("files pulled");
        } catch (Throwable e) {
            Log.e(TAG, "icons sync failed " + e);
            return false;
        }
    }

    private static void loop(Context ctx) {
        String adbPath = new File(ctx.getFilesDir(), "adb").getAbsolutePath();
        String home = ctx.getFilesDir().getAbsolutePath();
        String local = localHelper(ctx);
        SharedPreferences sp = ctx.getSharedPreferences("beetle_rc", 0);
        String lastHost = sp.getString("host", null);
        int tick = 0;
        while (true) {
            tick++;
            try {
                if (!serverListening()) {
                    if (tick % 2 == 0) {
                        String st = adb(adbPath, home, "start-server");
                        Log.e(TAG, "server start -> " + st.trim().replace('\n', ' '));
                        if (lastHost != null) {
                            Log.e(TAG, "reconnect " + lastHost + " -> " + adb(adbPath, home, "connect " + lastHost).trim());
                        }
                    }
                } else {
                    String out = adb(adbPath, home, "devices");
                    Matcher m = DEV.matcher(out);
                    String host = m.find() ? m.group(1) : null;
                    if (host != null) {
                        if (!host.equals(lastHost)) {
                            lastHost = host;
                            sp.edit().putString("host", host).commit();
                            Log.e(TAG, "remember host " + host);
                            installWrapper(ctx, host);
                        }
                        if (local != null && tick % 15 == 1) {
                            ensureHelper(adbPath, home, local);
                        }
                        if (!sp.getBoolean("icons_" + host, false) && tick % 5 == 2) {
                            if (syncIcons(ctx, adbPath, home, host)) {
                                sp.edit().putBoolean("icons_" + host, true).commit();
                                Log.e(TAG, "icons synced for " + host);
                            }
                        }
                    } else if (lastHost != null) {
                        String r = adb(adbPath, home, "connect " + lastHost);
                        Log.e(TAG, "reconnect " + lastHost + " -> " + r.trim());
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "loop " + e);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }
}








