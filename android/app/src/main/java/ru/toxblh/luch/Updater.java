// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Self-update from GitHub releases, only in the "github" build (BuildConfig.SELF_UPDATE): F-Droid and Play update the
 * app themselves and do not allow an app to do it. docs/updates.md has the whole story.
 *
 * A release is taken only if it is final (not a draft or a pre-release), newer than this app, has the signed
 * {@link #ASSET} with a SHA-256 digest, the download matches that digest, and the APK is signed with the same key as
 * the running app. The system installer checks the key again.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/Toxblh/luch-android/releases/latest";
    static final String ASSET = "luch-release.apk";
    private static final int TIMEOUT_MS = 15_000;
    private static final long MAX_APK = 50L * 1024 * 1024;

    /** A release newer than this app. */
    static final class Release {
        final String version, url, sha256;

        Release(String version, String url, String sha256) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    /** The newest release found by the last check, or null. Shown on the status screen. */
    static volatile Release available;
    /** Downloading or waiting for the installer. */
    static volatile boolean installing;
    private static final long FIRST_CHECK_MS = 60_000, CHECK_EVERY_MS = 24 * 60 * 60 * 1000L;

    private Updater() {
    }

    static boolean enabled() {
        return BuildConfig.SELF_UPDATE;
    }

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares dotted version numbers ("v0.10" > "0.9", "1.0" == "1.0.0"); anything after a number is ignored. */
    static int compare(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] numbers(String v) {
        String[] parts = v.trim().replaceFirst("^[vV]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceFirst("\\D.*$", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }

    /** The release's signed APK, or null if it has none (with a digest) or is not final. Does not compare versions. */
    static Release parse(JSONObject release) {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String version = release.optString("tag_name").replaceFirst("^[vV]", "");
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null || !ASSET.equals(a.optString("name"))) continue;
            String digest = a.optString("digest");
            if (!digest.matches("sha256:[0-9a-f]{64}")) return null;
            String url = a.optString("browser_download_url");
            if (url.isEmpty() || version.isEmpty()) return null;
            return new Release(version, url, digest.substring("sha256:".length()));
        }
        return null;
    }

    /** Asks {@code latestUrl} (GitHub's latest release) for a release newer than this app; null if there is none. */
    static Release check(Context context, String latestUrl) throws IOException {
        try {
            Release r = parse(new JSONObject(new String(get(latestUrl, 1024 * 1024), StandardCharsets.UTF_8)));
            return r != null && compare(r.version, currentVersion(context)) > 0 ? r : null;
        } catch (JSONException e) {
            throw new IOException("not a release: " + e.getMessage());
        }
    }

    /** Downloads the APK to the cache and checks its digest; throws if it does not match. */
    static File download(Context context, Release r) throws IOException {
        File f = new File(context.getCacheDir(), "update.apk");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = open(r.url);
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[64 * 1024];
            for (int n; (n = in.read(b)) > 0; ) {
                total += n;
                if (total > MAX_APK) throw new IOException("APK larger than " + MAX_APK);
                sha.update(b, 0, n);
                out.write(b, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (!hex(sha.digest()).equals(r.sha256)) {
            f.delete();
            throw new IOException("downloaded APK does not match the release's digest");
        }
        return f;
    }

    /** Whether the APK is signed with the same key as this app (an update from anyone else would be refused anyway). */
    static boolean sameSigner(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        try {
            Signature[] mine, theirs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo other = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (other == null || other.signingInfo == null || self.signingInfo == null) return false;
                if (!context.getPackageName().equals(other.packageName)) return false;
                mine = self.signingInfo.getApkContentsSigners();
                theirs = other.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                PackageInfo other = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
                if (other == null || !context.getPackageName().equals(other.packageName)) return false;
                mine = self.signatures;
                theirs = other.signatures;
            }
            return mine != null && theirs != null && mine.length > 0 && Arrays.equals(mine, theirs);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Hands the APK to the system installer. Once Luch installed itself (Android 12+), later updates go through
     * without a question. If the system wants to ask anyway, {@link UpdateReceiver} brings up its dialog only when the
     * person asked for the update ({@code byUser}); a background update is dropped instead of popping up over a film.
     */
    static void install(Context context, File apk, boolean byUser) throws IOException {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("luch.apk", 0, apk.length())) {
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(context, UpdateReceiver.class).putExtra(UpdateReceiver.BY_USER, byUser);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(context, id, done, flags).getIntentSender());
        }
        Log.i(LuchService.TAG, "update handed to the installer: " + apk.length() + " bytes");
    }

    /**
     * Checks a minute after the service starts and then once a day. Installs by itself only when the system will not
     * ask anything (it would pop up over whatever is on the TV) and nothing is playing; otherwise the status screen
     * offers the update.
     */
    static void schedule(Context context, android.os.Handler main) {
        if (!enabled()) return;
        Context app = context.getApplicationContext();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            new Thread(() -> {
                try {
                    Release r = check(app, latestUrl(app));
                    available = r;
                    Luch.notifyListeners();
                    if (r != null) Log.i(LuchService.TAG, "update available: " + r.version);
                    if (r != null && quiet(app) && idle(app)) start(app, r, false);
                } catch (IOException e) {
                    Log.i(LuchService.TAG, "update check: " + e.getMessage());
                }
            }, "luch-update-check").start();
            main.postDelayed(tick[0], CHECK_EVERY_MS);
        };
        main.postDelayed(tick[0], FIRST_CHECK_MS);
    }

    /**
     * Debug builds only: a release feed of one's own in files/update-url, to try an update on an emulator
     * (docs/updates.md). Release builds always ask GitHub.
     */
    private static String latestUrl(Context context) {
        if (BuildConfig.DEBUG) {
            try {
                String url = new String(java.nio.file.Files.readAllBytes(
                        new File(context.getFilesDir(), "update-url").toPath()), StandardCharsets.UTF_8).trim();
                if (!url.isEmpty()) return url;
            } catch (IOException ignored) {
                // no override
            }
        }
        return LATEST;
    }

    /** Android 12+ lets an app that installed itself update itself without asking. */
    static boolean quiet(Context context) {
        if (Build.VERSION.SDK_INT < 31) return false;
        PackageManager pm = context.getPackageManager();
        try {
            String installer = pm.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName();
            return context.getPackageName().equals(installer) && pm.canRequestPackageInstalls();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean idle(Context context) {
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        return MirrorSession.current == null && !Bench.running() && (audio == null || !audio.isMusicActive());
    }

    /** Downloads, checks and installs {@code r} on a thread of its own; also from the status screen's button. */
    static void start(Context context, Release r, boolean byUser) {
        Context app = context.getApplicationContext();
        synchronized (Updater.class) {
            if (installing) return;
            installing = true;
        }
        Luch.notifyListeners();
        new Thread(() -> {
            try {
                update(app, r, byUser);
            } catch (IOException e) {
                Log.w(LuchService.TAG, "update " + r.version + " failed: " + e.getMessage());
                // A wrong digest or key is worth knowing about; a network hiccup is not.
                if (String.valueOf(e.getMessage()).contains("digest") || String.valueOf(e.getMessage()).contains("key")) {
                    Reports.error(app, "update " + r.version + ": " + e.getMessage(), null, null);
                }
                installing = false;
                Luch.notifyListeners();
            }
        }, "luch-update").start();
    }

    /** Check, download, verify and install, in one go (call off the main thread). */
    static void update(Context context, Release r, boolean byUser) throws IOException {
        File apk = download(context, r);
        if (!sameSigner(context, apk)) {
            apk.delete();
            throw new IOException("the release APK is signed with another key");
        }
        install(context, apk, byUser);
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            for (int n; (n = in.read(b)) > 0; ) {
                if (out.size() + n > max) throw new IOException("answer too large");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);   // GitHub's asset links redirect to its storage (https to https)
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Luch");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return c;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
