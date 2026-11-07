package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer build of the launcher in the GitHub repository it was built from (the release
 * that CI publishes for the branch), offers to download it and installs it with PackageInstaller.
 *
 * The version of the installed app is identified by the commit that is part of its version name
 * (amethyst-legacy-DATE-COMMIT-debug-X.Y.Z); the release stores the full commit it was built from.
 * Local builds have no update source configured, so nothing is checked for them.
 */
public final class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "update_checker";
    private static final String PREF_LAST_CHECK = "last_check";
    private static final long MIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final String APK_ASSET_NAME = "app-debug.apk";
    private static final Pattern VERSION_COMMIT = Pattern.compile("-([0-9a-f]{7,40})-(?:debug|release)-");
    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private UpdateChecker() {}

    private static final class Release {
        String commit;
        String apkUrl;
        long apkSize;
    }

    /** Checks (in the background) whether a newer build exists. Safe to call on every launch. */
    public static void checkAsync(Activity activity, boolean manual) {
        final String repo = BuildConfig.UPDATE_REPO;
        final String tag = BuildConfig.UPDATE_TAG;
        if (repo.isEmpty() || tag.isEmpty()) return; // local build: no update source

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong(PREF_LAST_CHECK, 0) < MIN_CHECK_INTERVAL_MS) return;
        if (!sBusy.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                Release release = fetchRelease(repo, tag);
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                if (release != null && isNewer(release)) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing()) showUpdateDialog(activity, release);
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not check for updates", e);
            } finally {
                sBusy.set(false);
            }
        }, "UpdateChecker").start();
    }

    private static String localCommit() {
        Matcher matcher = VERSION_COMMIT.matcher(BuildConfig.VERSION_NAME);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isNewer(Release release) {
        String local = localCommit();
        if (local == null || release.commit == null || release.apkUrl == null) return false;
        if (!release.commit.matches("[0-9a-f]{40}")) return false;
        return !release.commit.startsWith(local);
    }

    private static Release fetchRelease(String repo, String tag) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + repo + "/releases/tags/" + tag);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Amethyst-Updater");
        try {
            if (connection.getResponseCode() != 200) return null;
            StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) body.append(new String(buffer, 0, read, "UTF-8"));
            }
            JSONObject json = new JSONObject(body.toString());
            Release release = new Release();
            release.commit = json.optString("target_commitish", null);
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (APK_ASSET_NAME.equals(asset.optString("name"))) {
                        release.apkUrl = asset.optString("browser_download_url", null);
                        release.apkSize = asset.optLong("size", 0);
                        break;
                    }
                }
            }
            return release;
        } finally {
            connection.disconnect();
        }
    }

    private static void showUpdateDialog(Activity activity, Release release) {
        String shortCommit = release.commit.substring(0, 7);
        String size = String.format(Locale.US, "%.0f", release.apkSize / 1048576f);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(activity.getString(R.string.update_available_message, shortCommit, size))
                .setPositiveButton(R.string.update_now, (dialog, which) -> download(activity, release))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void download(Activity activity, Release release) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) Tools.dpToPx(20);
        layout.setPadding(pad, pad, pad, pad / 2);
        TextView label = new TextView(activity);
        label.setText(R.string.update_downloading);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = pad / 2;
        layout.addView(label);
        layout.addView(bar, barParams);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
                .show();

        new Thread(() -> {
            File apk = new File(activity.getCacheDir(), "update/" + APK_ASSET_NAME);
            try {
                //noinspection ResultOfMethodCallIgnored
                apk.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                apk.delete();

                HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "Amethyst-Updater");
                long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : release.apkSize;
                long done = 0;
                long lastUpdate = 0;
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled.get()) throw new InterruptedException("cancelled");
                        out.write(buffer, 0, read);
                        done += read;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastUpdate > 150) {
                            lastUpdate = now;
                            final int progress = (int) (done * 1000 / total);
                            activity.runOnUiThread(() -> bar.setProgress(progress));
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                if (release.apkSize > 0 && apk.length() != release.apkSize)
                    throw new Exception("incomplete download (" + apk.length() + "/" + release.apkSize + ")");

                activity.runOnUiThread(() -> progressDialog.dismiss());
                install(activity, apk);
            } catch (InterruptedException e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                activity.runOnUiThread(progressDialog::dismiss);
            } catch (Exception e) {
                Log.e(TAG, "Update download failed", e);
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity, activity.getString(R.string.update_download_failed, String.valueOf(e.getMessage())),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "UpdateDownload").start();
    }

    /** Installs the APK with a PackageInstaller session (the user only has to confirm). */
    private static void install(Activity activity, File apk) throws Exception {
        Context context = activity.getApplicationContext();
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // Later updates can skip the confirmation when this app installed the previous one
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                session.fsync(out);
            }
            Intent callback = new Intent(context, UpdateInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback, flags);
            session.commit(pending.getIntentSender());
        }
    }
}
