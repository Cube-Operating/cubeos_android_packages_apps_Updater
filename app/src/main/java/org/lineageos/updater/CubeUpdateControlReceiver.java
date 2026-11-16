/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.misc.AutoUpdate;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.CubeUpdateState;
import org.lineageos.updater.misc.UpdateSchedule;

/**
 * CubeOS: what Cube Settings › Software update asks of Cube Update. Guarded in the manifest by
 * org.cubeapp.permission.SYSTEM_UPDATE (signature): only CubeOS's own platform-signed apps.
 *
 * <ul>
 *   <li>{@link #ACTION_CHECK}: fetch the update list now.</li>
 *   <li>{@link #ACTION_UPDATE_NOW}: fetch the list, then download and install the newest build on
 *       any network (the person asked), even with automatic updates off.</li>
 *   <li>{@link #ACTION_RESTART}: restart now to finish an installed update.</li>
 *   <li>{@link #ACTION_SCHEDULE}: update at a chosen time (see {@link UpdateSchedule}).</li>
 * </ul>
 */
public class CubeUpdateControlReceiver extends BroadcastReceiver {
    private static final String TAG = "CubeUpdateControl";

    public static final String ACTION_CHECK = "org.cubeapp.updater.action.CHECK";
    public static final String ACTION_UPDATE_NOW = "org.cubeapp.updater.action.UPDATE_NOW";
    public static final String ACTION_RESTART = "org.cubeapp.updater.action.RESTART";
    /** Extra {@link #EXTRA_AT}: when to update (epoch ms); 0 cancels the schedule. */
    public static final String ACTION_SCHEDULE = "org.cubeapp.updater.action.SCHEDULE";
    public static final String EXTRA_AT = "at";
    /** Sent by Cube Update's own alarm when the scheduled time comes. */
    public static final String ACTION_SCHEDULED_RUN = "org.cubeapp.updater.action.SCHEDULED_RUN";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CHECK.equals(action)) {
            Log.d(TAG, "Check requested");
            CubeUpdateState.checking(context);
            UpdatesCheckReceiver.checkNow(context);
        } else if (ACTION_UPDATE_NOW.equals(action)) {
            Log.d(TAG, "Update now requested");
            AutoUpdate.requestNow(context);
            CubeUpdateState.checking(context);
            UpdatesCheckReceiver.checkNow(context);
        } else if (ACTION_SCHEDULE.equals(action)) {
            UpdateSchedule.set(context, intent.getLongExtra(EXTRA_AT, 0));
        } else if (ACTION_SCHEDULED_RUN.equals(action)) {
            Log.i(TAG, "Scheduled update time");
            UpdateSchedule.run(context);
        } else if (ACTION_RESTART.equals(action)) {
            boolean waiting = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
            if (waiting) {
                Log.i(TAG, "Restart requested to finish the update");
                Intent reboot = new Intent(context, UpdaterReceiver.class);
                reboot.setAction(UpdaterReceiver.ACTION_INSTALL_REBOOT);
                context.sendBroadcast(reboot);
            }
        }
    }
}