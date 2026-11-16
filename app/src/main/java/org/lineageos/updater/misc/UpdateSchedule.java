/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.misc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.CubeUpdateControlReceiver;
import org.lineageos.updater.UpdaterReceiver;
import org.lineageos.updater.controller.NightlyRestartJob;

/**
 * CubeOS: "Schedule update" in Cube Settings › Software update. At the chosen time the phone
 * downloads the newest build if it hasn't yet, installs it and restarts to finish, without
 * waiting for the night. Settings.Global {@code cube_update_scheduled} holds the time (epoch ms,
 * 0 = none) so Settings can show it; a boot re-arms it.
 */
public final class UpdateSchedule {
    private static final String TAG = "UpdateSchedule";

    public static final String KEY_SCHEDULED = "cube_update_scheduled";
    private static final String PREF_RESTART_WHEN_INSTALLED = "cube_update_restart_when_installed";

    private UpdateSchedule() {
    }

    public static long scheduledAt(Context context) {
        return Settings.Global.getLong(context.getContentResolver(), KEY_SCHEDULED, 0);
    }

    /** Schedules the update for [atMillis]; 0 or a time in the past cancels it. */
    public static void set(Context context, long atMillis) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(context);
        am.cancel(pi);
        if (atMillis <= System.currentTimeMillis()) {
            put(context, 0);
            Log.d(TAG, "Schedule cleared");
            return;
        }
        // Inexact is fine for an update; allowed while idle so a sleeping phone still wakes.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
        put(context, atMillis);
        Log.d(TAG, "Update scheduled for " + atMillis);
    }

    /** After a boot: re-arms a schedule still in the future (alarms don't survive a restart). */
    public static void rearm(Context context) {
        long at = scheduledAt(context);
        if (at > 0) {
            set(context, at);
        }
    }

    /** The scheduled time has come: update now, and restart as soon as it is installed. */
    public static void run(Context context) {
        put(context, 0);
        boolean waiting = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
        if (waiting) {
            restartNowOrTonight(context);
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_RESTART_WHEN_INSTALLED, true).apply();
        AutoUpdate.requestNow(context);
        CubeUpdateState.checking(context);
        org.lineageos.updater.UpdatesCheckReceiver.checkNow(context);
    }

    /** True once, when this installed update came from a schedule (so it restarts at once). */
    public static boolean consumeRestartWhenInstalled(Context context) {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean yes = prefs.getBoolean(PREF_RESTART_WHEN_INSTALLED, false);
        prefs.edit().remove(PREF_RESTART_WHEN_INSTALLED).apply();
        return yes;
    }

    /** Restarts now unless a call is going on; then leaves it to tonight's restart. */
    public static void restartNowOrTonight(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int mode = audio == null ? AudioManager.MODE_NORMAL : audio.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE) {
            NightlyRestartJob.schedule(context);
            return;
        }
        Intent reboot = new Intent(context, UpdaterReceiver.class);
        reboot.setAction(UpdaterReceiver.ACTION_INSTALL_REBOOT);
        context.sendBroadcast(reboot);
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, CubeUpdateControlReceiver.class);
        intent.setAction(CubeUpdateControlReceiver.ACTION_SCHEDULED_RUN);
        return PendingIntent.getBroadcast(context, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void put(Context context, long at) {
        try {
            Settings.Global.putLong(context.getContentResolver(), KEY_SCHEDULED, at);
        } catch (SecurityException e) {
            Log.w(TAG, "Can't write " + KEY_SCHEDULED, e);
        }
    }
}
