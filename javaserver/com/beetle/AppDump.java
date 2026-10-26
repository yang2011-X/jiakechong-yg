package com.beetle;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Replacement for com.didjdk.adbhelper.Server (the app's package dumper).
 * The original uses the removed hidden API android.content.pm.PackageParser,
 * which Android 14+ rejects for framework-res.apk, so the app list is always empty.
 * This one only uses public PackageManager APIs and prints the same JSON lines.
 */
public class AppDump {

    private static final int ICON_PX = 96;

    public static void main(String[] args) {
        String base = args.length > 0 ? args[0] : "/data/local/tmp";
        File iconDir = new File(base, "icons");
        boolean wantIcons = !(args.length > 1 && "noicon".equals(args[1]));
        if (wantIcons) {
            iconDir.mkdirs();
        }
        try {
            Context ctx = systemContext();
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            System.out.println("====loading " + apps.size() + " packages====");
            for (int i = 0; i < apps.size(); i++) {
                ApplicationInfo ai = apps.get(i);
                try {
                    System.out.println(dumpOne(pm, ai, iconDir, wantIcons));
                } catch (Throwable t) {
                    // never abort the whole listing for one bad package
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Context systemContext() throws Exception {
        try {
            if (android.os.Looper.myLooper() == null) {
                Class<?> lp = Class.forName("android.os.Looper");
                java.lang.reflect.Method pm2 = lp.getDeclaredMethod("prepareMainLooper");
                pm2.setAccessible(true);
                pm2.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread;
        try {
            java.lang.reflect.Method m = at.getDeclaredMethod("systemMain");
            m.setAccessible(true);
            thread = m.invoke(null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            System.err.println("systemMain cause: " + c);
            if (c != null) {
                c.printStackTrace();
            }
            throw e;
        }
        return (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
    }

    private static String dumpOne(PackageManager pm, ApplicationInfo ai, File iconDir, boolean wantIcons) {
        String pkg = ai.packageName == null ? "" : ai.packageName;
        String label = "";
        try {
            CharSequence cs = pm.getApplicationLabel(ai);
            label = cs == null ? pkg : cs.toString();
        } catch (Throwable t) {
            label = pkg;
        }
        String versionName = "";
        long versionCode = 0;
        long firstInstall = 0;
        long lastUpdate = 0;
        int minSdk = 0;
        int targetSdk = 0;
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            versionName = pi.versionName == null ? "" : pi.versionName;
            versionCode = longVersionCode(pi);
            firstInstall = pi.firstInstallTime;
            lastUpdate = pi.lastUpdateTime;
            if (pi.applicationInfo != null) {
                targetSdk = pi.applicationInfo.targetSdkVersion;
                minSdk = intField(pi.applicationInfo, "minSdkVersion");
            }
        } catch (Throwable ignored) {
        }
        boolean hasIcon = false;
        if (wantIcons && ai.icon != 0) {
            try {
                android.content.res.Resources res = pm.getResourcesForApplication(ai.packageName);
                Drawable d = res.getDrawable(ai.icon, null);
                Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, ICON_PX, ICON_PX);
                d.draw(canvas);
                File out = new File(iconDir, pkg + ".png");
                FileOutputStream fos = new FileOutputStream(out);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                bmp.recycle();
                hasIcon = out.length() > 0;
            } catch (Throwable ignored) {
            }
        }
        long apkSize = 0;
        try {
            apkSize = new File(ai.sourceDir).length();
        } catch (Throwable ignored) {
        }
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        string(sb, "package", pkg).append(',');
        string(sb, "apkPath", ai.sourceDir == null ? "" : ai.sourceDir).append(',');
        sb.append("\"apkSize\":").append(apkSize).append(',');
        string(sb, "label", label).append(',');
        sb.append("\"hasIcon\":").append(hasIcon).append(',');
        sb.append("\"flags\":").append(system ? 1 : 0).append(',');
        sb.append("\"enabled\":").append(ai.enabled).append(',');
        string(sb, "versionName", versionName).append(',');
        sb.append("\"versionCode\":").append((int) versionCode).append(',');
        sb.append("\"minSdkVersion\":").append(minSdk).append(',');
        sb.append("\"targetSdkVersion\":").append(targetSdk).append(',');
        sb.append("\"firstInstallTime\":").append(firstInstall).append(',');
        sb.append("\"lastUpdateTime\":").append(lastUpdate).append(',');
        string(sb, "mainActivity", "").append(',');
        string(sb, "permission", "");
        sb.append('}');
        return sb.toString();
    }

    private static long longVersionCode(PackageInfo pi) {
        try {
            Object v = PackageInfo.class.getMethod("getLongVersionCode").invoke(pi);
            return ((Number) v).longValue();
        } catch (Throwable ignored) {
        }
        return pi.versionCode;
    }

    private static int intField(Object o, String name) {
        try {
            return o.getClass().getField(name).getInt(o);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static StringBuilder string(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
        return sb;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}



