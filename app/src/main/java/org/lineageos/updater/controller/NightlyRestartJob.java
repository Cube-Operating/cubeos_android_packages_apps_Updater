/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.controller;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.UpdaterReceiver;
import org.lineageos.updater.misc.AutoUpdate;
import org.lineageos.updater.misc.Constants;

import java.util.Calendar;

/**
 * CubeOS: an installed update only runs after a restart. With automatic updates on, the phone
 * restarts by itself at night (01:00–05:00) while it is charging and idle, never during a call,
 * so phones that are never restarted by hand still end up on the new version. The person can
 * always restart sooner from Settings › Software update or the notification.
 */
public class NightlyRestartJob extends JobService {
    private static final String TAG = "NightlyRestartJob";
    private static final int JOB_ID = 2;
    static final int WINDOW_START_HOUR = 1;
    static final int WINDOW_END_HOUR = 5;

    /** Arms the nightly restart for an update waiting for it (no-op with automatic updates off). */
    public static void schedule(Context context) {
        if (!AutoUpdate.isEnabled(context)) {
            return;
        }
        JobScheduler js = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NightlyRestartJob.class))
                .setMinimumLatency(millisUntilWindow(Calendar.getInstance()))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .build();
        js.schedule(job);
        Log.d(TAG, "Nightly restart armed");
    }

    public static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    /** 0 inside tonight's window, else the time until it opens. */
    static long millisUntilWindow(Calendar now) {
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour >= WINDOW_START_HOUR && hour < WINDOW_END_HOUR) {
            return 0;
        }
        Calendar start = (Calendar) now.clone();
        start.set(Calendar.HOUR_OF_DAY, WINDOW_START_HOUR);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        if (!start.after(now)) {
            start.add(Calendar.DAY_OF_MONTH, 1);
        }
        return start.getTimeInMillis() - now.getTimeInMillis();
    }

    static boolean inWindow(Calendar now) {
        int hour = now.get(Calendar.HOUR_OF_DAY);
        return hour >= WINDOW_START_HOUR && hour < WINDOW_END_HOUR;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean waiting = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
        if (!waiting || !AutoUpdate.isEnabled(this)) {
            return false;
        }
        AudioManager audio = getSystemService(AudioManager.class);
        int mode = audio == null ? AudioManager.MODE_NORMAL : audio.getMode();
        boolean inCall = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
        if (!inWindow(Calendar.getInstance()) || inCall) {
            schedule(this);
            return false;
        }
        Log.i(TAG, "Restarting to finish the update");
        Intent reboot = new Intent(this, UpdaterReceiver.class);
        reboot.setAction(UpdaterReceiver.ACTION_INSTALL_REBOOT);
        sendBroadcast(reboot);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
