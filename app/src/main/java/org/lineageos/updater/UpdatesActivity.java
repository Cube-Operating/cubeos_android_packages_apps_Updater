/*
 * Copyright (C) 2017-2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.misc.AutoUpdate;
import org.lineageos.updater.misc.BuildInfoUtils;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private boolean mIsTV;

    // CubeOS: hero state (phones). See updateHero().
    private enum HeroState {
        UNKNOWN,
        CHECKING,
        CHECK_FAILED,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        INSTALLING,
        RESTART,
    }

    private boolean mChecking;
    private boolean mCheckFailed;
    private boolean mListLoaded;
    private String mHeroKey;

    private final ContentObserver mAutoUpdateObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    bindAutoUpdates(true);
                }
            };

    private UpdateInfo mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                    updateHero();
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    List<UpdateInfo> sortedUpdates =
                            mUpdaterService.getUpdaterController().getUpdates();
                    if (sortedUpdates.isEmpty()) {
                        setUpdatesShown(false);
                    } else {
                        updateHero();
                    }
                }
            }
        };

        TextView headerTitle = findViewById(R.id.header_title);
        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        if (!mIsTV) {
            setUpPhoneLayout();
            headerTitle.setText(getString(R.string.hero_title,
                    BuildInfoUtils.getBuildVersion()));
            // CubeOS: the build name, never the "Android" version (owner's request, 2026-10-19).
            String build = BuildInfoUtils.getDisplayVersion();
            if (build.isEmpty()) {
                headerBuildVersion.setVisibility(View.GONE);
            } else {
                headerBuildVersion.setText(getString(R.string.hero_build, build));
            }
        } else {
            headerTitle.setText(getString(R.string.header_title_text,
                    BuildInfoUtils.getBuildVersion()));
            // CubeOS: no "Android" line under the CubeOS version (owner's request, 2026-10-19).
            headerBuildVersion.setVisibility(View.GONE);

            TextView headerBuildDate = findViewById(R.id.header_build_date);
            headerBuildDate.setText(StringGenerator.getDateLocalizedUTC(this,
                    DateFormat.LONG, BuildInfoUtils.getBuildDateTimestamp()));

            findViewById(R.id.refresh).setOnClickListener(v -> downloadUpdatesList(true));
            findViewById(R.id.preferences).setOnClickListener(v -> showPreferencesDialog());
        }

        updateLastCheckedString();

        if (mIsTV) {
            // CubeOS: on phones the welcome note is gone; only the newest build is offered anyway.
            maybeShowWelcomeMessage();
        }
    }

    /** CubeOS: toolbar, edge-to-edge insets, the hero button and Automatic Updates. */
    private void setUpPhoneLayout() {
        // Draw behind the system bars; the insets listener below pads the content instead.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        final View appBar = findViewById(R.id.app_bar);
        final NestedScrollView scrollView = findViewById(R.id.scroll_view);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_container),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
                    appBar.setPadding(bars.left, bars.top, bars.right, 0);
                    scrollView.setPadding(bars.left, 0, bars.right, bars.bottom);
                    return insets;
                });

        // The small title and hairline appear once the big title scrolls under the toolbar.
        final View toolbarTitle = findViewById(R.id.toolbar_title);
        final View toolbarDivider = findViewById(R.id.toolbar_divider);
        final View headerTitle = findViewById(R.id.header_title);
        scrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    float start = headerTitle.getTop();
                    float range = Math.max(1, headerTitle.getHeight());
                    float alpha = Math.min(1f, Math.max(0f, (scrollY - start) / range));
                    toolbarTitle.setAlpha(alpha);
                    toolbarDivider.setAlpha(Math.min(1f, scrollY / 24f));
                });

        findViewById(R.id.check_button).setOnClickListener(v -> {
            if (!mChecking) {
                downloadUpdatesList(true);
            }
        });

        findViewById(R.id.auto_update_row).setOnClickListener(v ->
                setAutoUpdateSetting(AutoUpdate.SETTING, !AutoUpdate.isEnabled(this)));
        findViewById(R.id.auto_update_mobile_row).setOnClickListener(v ->
                setAutoUpdateSetting(AutoUpdate.SETTING_MOBILE, !AutoUpdate.mobileAllowed(this)));
        bindAutoUpdates(false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);

        if (!mIsTV) {
            // CubeOS: Cube Settings › Software update switches the same settings.
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(AutoUpdate.SETTING), false, mAutoUpdateObserver);
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(AutoUpdate.SETTING_MOBILE), false,
                    mAutoUpdateObserver);
            bindAutoUpdates(false);
        }
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (!mIsTV) {
            getContentResolver().unregisterContentObserver(mAutoUpdateObserver);
        }
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    // Update UI
                    getUpdatesList();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);
        // CubeOS: only the newest build is offered; drop what's left of older ones (a paused
        // download, a downloaded file), unless it's being installed or waiting for a restart.
        for (UpdateInfo old : controller.getUpdates()) {
            String id = old.getDownloadId();
            // A local update (Local update import) is never on the server's list: keep it.
            if (Update.LOCAL_ID.equals(id) || updatesOnline.contains(id)
                    || controller.isInstallingUpdate(id) || controller.isWaitingForReboot(id)) {
                continue;
            }
            controller.pauseDownload(id);
            controller.deleteUpdate(id);
        }

        // CubeOS: on phones the hero shows the result ("up to date" / "Update available").
        if (manualRefresh && mIsTV) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        mListLoaded = true;
        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            setUpdatesShown(false);
        } else {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
            setUpdatesShown(true);
        }
    }

    /** Shows the update list, or the "no updates" state, and refreshes the hero to match. */
    private void setUpdatesShown(boolean shown) {
        if (!mIsTV) {
            beginHeroTransition();
        }
        findViewById(R.id.no_new_updates_view).setVisibility(shown ? View.GONE : View.VISIBLE);
        findViewById(R.id.recycler_view).setVisibility(shown ? View.VISIBLE : View.GONE);
        updateHero();
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            onCheckFailed();
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    refreshAnimationStop();
                    if (!cancelled) {
                        onCheckFailed();
                    }
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            onCheckFailed();
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    /** The update check didn't go through: plain words in the hero, a snackbar on TV. */
    private void onCheckFailed() {
        if (mIsTV) {
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }
        mCheckFailed = true;
        updateLastCheckedString();
        updateHero();
    }

    private void updateLastCheckedString() {
        TextView headerLastCheck = findViewById(R.id.header_last_check);
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        if (mIsTV) {
            String lastCheckString = getString(R.string.header_last_updates_check,
                    StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                    StringGenerator.getTimeLocalized(this, lastCheck));
            headerLastCheck.setText(lastCheckString);
            return;
        }
        // CubeOS: "Last checked today at 08:46", or plain words when the check failed.
        if (mCheckFailed && !mChecking) {
            headerLastCheck.setText(R.string.check_failed_detail);
        } else if (lastCheck <= 0) {
            headerLastCheck.setText(R.string.last_checked_never);
        } else {
            long millis = lastCheck * 1000;
            String time = StringGenerator.getTimeLocalized(this, lastCheck);
            if (DateUtils.isToday(millis)) {
                headerLastCheck.setText(getString(R.string.last_checked_today, time));
            } else if (DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS)) {
                headerLastCheck.setText(getString(R.string.last_checked_yesterday, time));
            } else {
                headerLastCheck.setText(getString(R.string.last_checked_date,
                        StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                        time));
            }
        }
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) {
            return;
        }
        if (!mIsTV) {
            // CubeOS: the card says what happened; "Download and Install" carries on to install.
            if (update.getStatus() == UpdateStatus.VERIFIED) {
                mAdapter.onUpdateVerified(downloadId);
            }
            return;
        }
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show();
    }

    private void refreshAnimationStart() {
        if (!mIsTV) {
            mChecking = true;
            mCheckFailed = false;
            updateLastCheckedString();
            updateHero();
        } else {
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        }
    }

    private void refreshAnimationStop() {
        if (!mIsTV) {
            mChecking = false;
            updateLastCheckedString();
            updateHero();
        } else {
            findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            if (mAdapter.getItemCount() > 0) {
                findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ CubeOS: the hero

    private void beginHeroTransition() {
        ViewGroup content = findViewById(R.id.content);
        TransitionManager.beginDelayedTransition(content, new AutoTransition().setDuration(220));
    }

    private HeroState computeHeroState() {
        if (mChecking) {
            return HeroState.CHECKING;
        }
        UpdaterController controller =
                mUpdaterService != null ? mUpdaterService.getUpdaterController() : null;
        boolean shown = mAdapter.getItemCount() > 0
                && findViewById(R.id.recycler_view).getVisibility() == View.VISIBLE;
        if (controller != null && shown) {
            for (UpdateInfo update : controller.getUpdates()) {
                if (controller.isWaitingForReboot(update.getDownloadId())) {
                    return HeroState.RESTART;
                }
            }
            if (controller.isInstallingUpdate()) {
                return HeroState.INSTALLING;
            }
            if (controller.hasActiveDownloads() || controller.isVerifyingUpdate()) {
                return HeroState.DOWNLOADING;
            }
            return HeroState.AVAILABLE;
        }
        if (mCheckFailed) {
            return HeroState.CHECK_FAILED;
        }
        return mListLoaded ? HeroState.UP_TO_DATE : HeroState.UNKNOWN;
    }

    /** Status line, last-checked line and the Check for Updates button, from the current state. */
    private void updateHero() {
        if (mIsTV) {
            return;
        }
        HeroState state = computeHeroState();
        boolean hasUpdate = state == HeroState.AVAILABLE || state == HeroState.DOWNLOADING
                || state == HeroState.INSTALLING || state == HeroState.RESTART;
        String key = state + "/" + mCheckFailed + "/" + hasUpdate;
        if (key.equals(mHeroKey)) {
            return;
        }
        if (mHeroKey != null) {
            beginHeroTransition();
        }
        mHeroKey = key;

        View status = findViewById(R.id.header_status);
        ImageView statusIcon = findViewById(R.id.header_status_icon);
        TextView statusText = findViewById(R.id.header_status_text);
        int icon = 0;
        int text = 0;
        switch (state) {
            case CHECKING:
                text = R.string.status_checking;
                break;
            case CHECK_FAILED:
                icon = R.drawable.ic_status_alert;
                text = R.string.status_check_failed;
                break;
            case UP_TO_DATE:
                icon = R.drawable.ic_status_check;
                text = R.string.status_up_to_date;
                break;
            case AVAILABLE:
                icon = R.drawable.ic_status_download;
                text = R.string.status_update_available;
                break;
            case DOWNLOADING:
                icon = R.drawable.ic_status_download;
                text = R.string.status_downloading;
                break;
            case INSTALLING:
                icon = R.drawable.ic_status_download;
                text = R.string.status_installing;
                break;
            case RESTART:
                icon = R.drawable.ic_status_restart;
                text = R.string.status_restart;
                break;
            default:
                break;
        }
        if (text == 0) {
            status.setVisibility(View.INVISIBLE);
        } else {
            statusText.setText(text);
            statusText.setTextColor(getColor(state == HeroState.CHECKING
                    ? R.color.cube_secondary_label : R.color.cube_label));
            if (icon != 0) {
                statusIcon.setImageResource(icon);
                statusIcon.setVisibility(View.VISIBLE);
            } else {
                statusIcon.setVisibility(View.GONE);
            }
            status.setVisibility(View.VISIBLE);
        }

        // Filled when checking is the thing to do; tinted once an update card is the focus.
        View button = findViewById(R.id.check_button);
        ProgressBar progress = findViewById(R.id.check_progress);
        TextView label = findViewById(R.id.check_label);
        boolean primary = !hasUpdate && !mChecking;
        button.setBackgroundResource(primary ? R.drawable.bg_pill_primary
                : R.drawable.bg_pill_tonal);
        label.setTextColor(getColor(primary ? R.color.cube_on_accent : R.color.cube_accent));
        progress.setVisibility(mChecking ? View.VISIBLE : View.GONE);
        label.setText(mChecking ? R.string.action_checking
                : mCheckFailed ? R.string.action_try_again : R.string.action_check_for_updates);
        button.setEnabled(!mChecking);
    }

    // ------------------------------------------------------- CubeOS: Automatic Updates

    /** Reflects cube_auto_update / cube_auto_update_mobile (also switched in Cube Settings). */
    private void bindAutoUpdates(boolean animate) {
        if (mIsTV) {
            return;
        }
        boolean on = AutoUpdate.isEnabled(this);
        boolean mobile = AutoUpdate.mobileAllowed(this);
        SwitchCompat autoSwitch = findViewById(R.id.auto_update_switch);
        SwitchCompat mobileSwitch = findViewById(R.id.auto_update_mobile_switch);
        View mobileGroup = findViewById(R.id.auto_update_mobile_group);
        TextView footer = findViewById(R.id.auto_update_footer);
        if (animate && (autoSwitch.isChecked() != on || mobileSwitch.isChecked() != mobile)) {
            ViewGroup content = findViewById(R.id.content);
            TransitionManager.beginDelayedTransition(content,
                    new AutoTransition().setDuration(220));
        }
        autoSwitch.setChecked(on);
        mobileSwitch.setChecked(mobile);
        mobileGroup.setVisibility(on ? View.VISIBLE : View.GONE);
        footer.setText(!on ? R.string.auto_update_footer_off
                : mobile ? R.string.auto_update_footer_on_mobile
                : R.string.auto_update_footer_on_wifi);
        View row = findViewById(R.id.auto_update_row);
        row.setContentDescription(getString(R.string.auto_update_switch));
        row.setStateDescription(getString(on ? R.string.state_on : R.string.state_off));
        View mobileRow = findViewById(R.id.auto_update_mobile_row);
        mobileRow.setContentDescription(getString(R.string.auto_update_mobile));
        mobileRow.setStateDescription(getString(mobile ? R.string.state_on : R.string.state_off));
    }

    private void setAutoUpdateSetting(String name, boolean enabled) {
        try {
            Settings.Global.putInt(getContentResolver(), name, enabled ? 1 : 0);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not change " + name, e);
            Toast.makeText(this, R.string.auto_update_failed, Toast.LENGTH_SHORT).show();
        }
        if (AutoUpdate.SETTING.equals(name)) {
            // Same as closing Advanced Options: the check schedule follows the setting.
            if (Utils.isUpdateCheckEnabled(this)) {
                UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
            } else {
                UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                UpdatesCheckReceiver.cancelUpdatesCheck(this);
            }
        }
        bindAutoUpdates(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        SwitchCompat meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                                    meteredNetworkWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }

    private void maybeShowWelcomeMessage() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, true)
                        .apply())
                .show();
    }
}
