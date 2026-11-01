/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.misc;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.lineageos.updater.controller.DownloadRetryJob;
import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * CubeOS: automatic updates, switched in Cube Settings › Software update (Settings.Global
 * cube_auto_update, on unless turned off; cube_auto_update_mobile, off unless turned on).
 *
 * After each update check the newest installable build is downloaded in the background (on an
 * unmetered network unless mobile data is allowed), installed once verified, and the usual
 * "restart to finish" notification follows. The phone never restarts by itself. Only downloads
 * started here are installed automatically; with the switch off nothing changes from before.
 */
public final class AutoUpdate {
    private static final String TAG = "AutoUpdate";

    public static final String SETTING = "cube_auto_update";
    public static final String SETTING_MOBILE = "cube_auto_update_mobile";
    private static final String PREF_AUTO_ID = "cube_auto_update_id";
    private static final String PREF_RETRY_DELAY = "cube_auto_update_retry_delay";
    private static final String PREF_RETRY_BYTES = "cube_auto_update_retry_bytes";

    private static final int RETRY_JOB_ID = 1;
    private static final long RETRY_MIN_MS = 30_000L;
    private static final long RETRY_MAX_MS = 30 * 60_000L;

    private AutoUpdate() {
    }

    public static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 1) == 1;
    }

    /** Whether automatic updates may download over mobile data, not only Wi-Fi. */
    public static boolean mobileAllowed(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING_MOBILE, 0) == 1;
    }

    /** Whether a download may start on the network the phone has now. */
    public static boolean canDownloadNow(Context context) {
        return Utils.isNetworkAvailable(context)
                && (!Utils.isNetworkMetered(context) || mobileAllowed(context));
    }

    /** True when [downloadId] was started by automatic updates (so it may also install by itself). */
    public static boolean isAutomatic(Context context, String downloadId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId != null && downloadId.equals(prefs.getString(PREF_AUTO_ID, null));
    }

    private static void markAutomatic(Context context, String downloadId) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_AUTO_ID, downloadId).apply();
    }

    /** Asks UpdaterService to run automatic updates now, if they're on and the network allows. */
    public static void maybeStart(Context context) {
        if (!isEnabled(context) || !canDownloadNow(context)) {
            return;
        }
        Intent intent = new Intent(context, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_AUTO_UPDATE);
        context.startForegroundService(intent);
    }

    /**
     * After a dropped download: tries again soon, once the phone has a network it may use. A file
     * that grew since the last failure starts the wait from the beginning again.
     */
    public static void scheduleRetry(Context context, long downloadedBytes) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long delay = downloadedBytes > prefs.getLong(PREF_RETRY_BYTES, 0)
                ? RETRY_MIN_MS : prefs.getLong(PREF_RETRY_DELAY, RETRY_MIN_MS);
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(RETRY_JOB_ID,
                new ComponentName(context, DownloadRetryJob.class))
                .setMinimumLatency(delay)
                .setRequiredNetworkType(mobileAllowed(context)
                        ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED)
                .build());
        prefs.edit()
                .putLong(PREF_RETRY_BYTES, downloadedBytes)
                .putLong(PREF_RETRY_DELAY, Math.min(delay * 2, RETRY_MAX_MS))
                .apply();
        Log.d(TAG, "Retrying the download in " + delay + " ms");
    }

    /** The download finished, or the person paused or deleted it: no more retries. */
    public static void cancelRetry(Context context) {
        context.getSystemService(JobScheduler.class).cancel(RETRY_JOB_ID);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_RETRY_BYTES)
                .remove(PREF_RETRY_DELAY)
                .apply();
    }

    /** The retry's time has come: carries on, or waits longer if the network isn't usable yet. */
    public static void retry(Context context) {
        if (canDownloadNow(context)) {
            maybeStart(context);
        } else if (isEnabled(context)) {
            scheduleRetry(context, PreferenceManager.getDefaultSharedPreferences(context)
                    .getLong(PREF_RETRY_BYTES, 0));
        }
    }

    /**
     * Downloads (or installs, if already downloaded) the newest installable build in the cached
     * update list. Returns false when there was nothing to do.
     */
    public static boolean run(Context context, UpdaterController controller) {
        UpdateInfo newest = newestInstallable(context);
        if (newest == null || controller.isInstallingUpdate()) {
            return false;
        }
        String id = newest.getDownloadId();
        controller.addUpdate(newest);
        UpdateInfo update = controller.getUpdate(id);
        if (update == null || controller.isWaitingForReboot(id)
                || update.getStatus() == UpdateStatus.INSTALLED) {
            return false;
        }
        markAutomatic(context, id);
        if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            Log.d(TAG, "Installing " + id);
            Utils.triggerUpdate(context, id);
        } else if (controller.isDownloading(id) || controller.isVerifyingUpdate(id)) {
            Log.d(TAG, "Already downloading " + id);
        } else if (update.getStatus() == UpdateStatus.AWAITING_CUBEX) {
            // Downloaded and signed, not yet checked on CUBEX: ask again. Automatic updates
            // never use "Install Anyway"; that is the person's choice alone.
            Log.d(TAG, "Checking " + id + " on CUBEX again");
            controller.recheckCubex(id);
        } else if ((update.getStatus() == UpdateStatus.PAUSED
                || update.getStatus() == UpdateStatus.PAUSED_ERROR)
                && update.getFile() != null && update.getFile().exists()) {
            // After a dropped connection too: starting over would lose the progress and leave the
            // partial file behind, and on a weak connection the download might never finish.
            Log.d(TAG, "Resuming " + id);
            controller.resumeDownload(id);
        } else {
            Log.d(TAG, "Downloading " + id);
            controller.startDownload(id);
        }
        return true;
    }

    private static UpdateInfo newestInstallable(Context context) {
        File json = Utils.getCachedUpdateList(context);
        if (!json.exists()) {
            return null;
        }
        try {
            List<UpdateInfo> updates = Utils.parseJson(json, true);
            UpdateInfo newest = null;
            for (UpdateInfo u : updates) {
                if (Utils.canInstall(u) && (newest == null || u.getTimestamp() > newest.getTimestamp())) {
                    newest = u;
                }
            }
            return newest;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read the update list", e);
            return null;
        }
    }
}
