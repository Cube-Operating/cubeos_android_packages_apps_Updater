/*
 * Copyright (C) 2026 Egoras / CubeOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.controller;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.lineageos.updater.misc.AutoUpdate;

/** CubeOS: runs when a dropped automatic download may try again (AutoUpdate.scheduleRetry). */
public class DownloadRetryJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        AutoUpdate.retry(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}