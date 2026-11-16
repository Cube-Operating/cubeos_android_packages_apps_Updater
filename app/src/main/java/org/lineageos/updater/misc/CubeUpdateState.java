/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.misc;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CubeOS: tells Cube Settings › Software update what Cube Update is doing, through Settings.Global
 * (Settings observes these keys; Cube Update holds WRITE_SECURE_SETTINGS):
 *
 * <ul>
 *   <li>{@code cube_update_state}: current | checking | available | downloading | paused |
 *       verifying | installing | restart | error</li>
 *   <li>{@code cube_update_version}: the waiting build's version ("1.7.1"), else empty</li>
 *   <li>{@code cube_update_build}: its build number ("1794318626"), else empty</li>
 *   <li>{@code cube_update_size}: its download size in bytes, else 0</li>
 *   <li>{@code cube_update_progress}: 0..100 while downloading or installing</li>
 *   <li>{@code cube_update_checked}: when the update list was last fetched (epoch ms)</li>
 * </ul>
 */
public final class CubeUpdateState {
    private static final String TAG = "CubeUpdateState";

    public static final String KEY_STATE = "cube_update_state";
    public static final String KEY_VERSION = "cube_update_version";
    public static final String KEY_BUILD = "cube_update_build";
    public static final String KEY_SIZE = "cube_update_size";
    public static final String KEY_PROGRESS = "cube_update_progress";
    public static final String KEY_CHECKED = "cube_update_checked";

    public static final String CURRENT = "current";
    public static final String CHECKING = "checking";
    public static final String AVAILABLE = "available";
    public static final String DOWNLOADING = "downloading";
    public static final String PAUSED = "paused";
    public static final String VERIFYING = "verifying";
    public static final String INSTALLING = "installing";
    public static final String RESTART = "restart";
    public static final String ERROR = "error";

    private static final Pattern BUILD = Pattern.compile("(\\d{9,})");

    private CubeUpdateState() {
    }

    /** The state for [update], from its status. */
    public static void publish(Context context, UpdateInfo update) {
        if (update == null) {
            return;
        }
        UpdateStatus status = update.getStatus();
        String state;
        int progress = 0;
        switch (status) {
            case STARTING:
            case DOWNLOADING:
                state = DOWNLOADING;
                progress = update.getProgress();
                break;
            case PAUSED:
                state = PAUSED;
                progress = update.getProgress();
                break;
            case INSTALLATION_SUSPENDED:
                state = PAUSED;
                progress = update.getInstallProgress();
                break;
            case PAUSED_ERROR:
            case VERIFICATION_FAILED:
            case INSTALLATION_FAILED:
                state = ERROR;
                progress = update.getProgress();
                break;
            case VERIFYING:
            case VERIFIED:
            case AWAITING_CUBEX:
                state = VERIFYING;
                break;
            case INSTALLING:
                state = INSTALLING;
                progress = update.getInstallProgress();
                break;
            case INSTALLED:
                state = RESTART;
                progress = 100;
                break;
            case DELETED:
            case INSTALLATION_CANCELLED:
            case UNKNOWN:
            default:
                state = AVAILABLE;
                break;
        }
        write(context, state, update, progress);
    }

    /** Only the progress moved (download or install). */
    public static void progress(Context context, int percent) {
        putInt(context, KEY_PROGRESS, Math.max(0, Math.min(100, percent)));
    }

    public static void checking(Context context) {
        putString(context, KEY_STATE, CHECKING);
    }

    /**
     * After the update list was fetched: "available" with the newest installable build, or
     * "current" when there is none. A download or install already under way keeps its state.
     */
    public static void afterCheck(Context context) {
        putLong(context, KEY_CHECKED, System.currentTimeMillis());
        String state = Settings.Global.getString(context.getContentResolver(), KEY_STATE);
        if (DOWNLOADING.equals(state) || INSTALLING.equals(state) || VERIFYING.equals(state)
                || RESTART.equals(state)) {
            return;
        }
        UpdateInfo newest = newestInstallable(context);
        if (newest == null) {
            write(context, CURRENT, null, 0);
        } else {
            write(context, AVAILABLE, newest, 0);
        }
    }

    /** The list could not be fetched; shown as "current" only if nothing better is known. */
    public static void checkFailed(Context context) {
        if (CHECKING.equals(Settings.Global.getString(context.getContentResolver(), KEY_STATE))) {
            putString(context, KEY_STATE, ERROR);
        }
    }

    /** After a boot: an update that was waiting for the restart is now running. */
    public static void afterBoot(Context context) {
        String state = Settings.Global.getString(context.getContentResolver(), KEY_STATE);
        if (RESTART.equals(state) || state == null) {
            write(context, CURRENT, null, 0);
        }
    }

    public static UpdateInfo newestInstallable(Context context) {
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

    private static void write(Context context, String state, UpdateInfo update, int progress) {
        String version = "";
        String build = "";
        long size = 0;
        if (update != null) {
            version = update.getVersion() == null ? "" : update.getVersion();
            Matcher m = BUILD.matcher(update.getName() == null ? "" : update.getName());
            if (m.find()) {
                build = m.group(1);
            }
            size = update.getFileSize();
        }
        putString(context, KEY_VERSION, version);
        putString(context, KEY_BUILD, build);
        putLong(context, KEY_SIZE, size);
        putInt(context, KEY_PROGRESS, progress);
        putString(context, KEY_STATE, state);
    }

    private static void putString(Context context, String key, String value) {
        try {
            Settings.Global.putString(context.getContentResolver(), key, value);
        } catch (SecurityException e) {
            Log.w(TAG, "Can't write " + key, e);
        }
    }

    private static void putInt(Context context, String key, int value) {
        try {
            Settings.Global.putInt(context.getContentResolver(), key, value);
        } catch (SecurityException e) {
            Log.w(TAG, "Can't write " + key, e);
        }
    }

    private static void putLong(Context context, String key, long value) {
        try {
            Settings.Global.putLong(context.getContentResolver(), key, value);
        } catch (SecurityException e) {
            Log.w(TAG, "Can't write " + key, e);
        }
    }
}
