/*
 * Copyright (C) 2017-2025 The LineageOS Project
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

import android.app.UiModeManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.misc.AutoUpdate;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdateListAdapter";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private final float mAlphaDisabledValue;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private final UpdatesListActivity mActivity;

    // CubeOS: the redesigned card (phones). The TV layout keeps the upstream look and labels.
    private final boolean mCubeLayout;

    // CubeOS: downloads started with "Download and Install" while this screen was open; once
    // verified, the usual install confirmation opens by itself.
    private final Set<String> mInstallWhenReady = new HashSet<>();

    private AlertDialog infoDialog;

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final Button mAction;
        private final ImageButton mMenu;

        private final TextView mBuildDate;
        private final TextView mBuildVersion;
        private final TextView mBuildSize;

        private final LinearLayout mProgress;
        private final ProgressBar mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        // CubeOS card only (null on TV)
        private final TextView mStatus;
        private final View mWhatsNew;

        public ViewHolder(final View view) {
            super(view);
            mAction = view.findViewById(R.id.update_action);
            mMenu = view.findViewById(R.id.update_menu);

            mBuildDate = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize = view.findViewById(R.id.build_size);

            mProgress = view.findViewById(R.id.progress);
            mProgressBar = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);

            mStatus = view.findViewById(R.id.update_status);
            mWhatsNew = view.findViewById(R.id.whats_new);
        }
    }

    public UpdatesListAdapter(UpdatesListActivity activity) {
        mActivity = activity;

        TypedValue tv = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();

        UiModeManager uiModeManager = mActivity.getSystemService(UiModeManager.class);
        mCubeLayout = uiModeManager.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        if (infoDialog != null) {
            infoDialog.dismiss();
        }
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    /** CubeOS: a status line on the card; 0 hides it. Errors are shown in red. */
    private void setStatus(ViewHolder viewHolder, @StringRes int text, boolean error) {
        if (viewHolder.mStatus == null) {
            return;
        }
        if (text == 0) {
            viewHolder.mStatus.setVisibility(View.GONE);
            return;
        }
        viewHolder.mStatus.setText(text);
        viewHolder.mStatus.setTextColor(mActivity.getColor(
                error ? R.color.cube_red : R.color.cube_secondary_label));
        viewHolder.mStatus.setVisibility(View.VISIBLE);
    }

    private void setWhatsNewVisible(ViewHolder viewHolder, boolean visible) {
        if (viewHolder.mWhatsNew != null) {
            viewHolder.mWhatsNew.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private String formatProgress(String downloaded, String total, CharSequence eta) {
        if (mCubeLayout) {
            return eta == null
                    ? mActivity.getString(R.string.card_progress, downloaded, total)
                    : mActivity.getString(R.string.card_progress_eta, downloaded, total, eta);
        }
        return eta == null
                ? mActivity.getString(R.string.list_download_progress_newer, downloaded, total)
                : mActivity.getString(R.string.list_download_progress_eta_newer, downloaded,
                        total, eta);
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        boolean canDelete = false;

        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            canDelete = true;
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(formatProgress(downloaded, total, etaString));
            } else {
                viewHolder.mProgressText.setText(formatProgress(downloaded, total, null));
            }
            boolean starting = update.getStatus() == UpdateStatus.STARTING;
            setStatus(viewHolder, starting ? R.string.card_starting : R.string.card_downloading,
                    false);
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true);
            viewHolder.mProgressBar.setIndeterminate(starting);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            if (mCubeLayout) {
                setStatus(viewHolder, notAB ? R.string.card_install_prep :
                        update.getFinalizing() ?
                                R.string.card_finalizing :
                                R.string.card_installing, false);
                viewHolder.mProgressText.setText(null);
            } else {
                viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                        update.getFinalizing() ?
                                R.string.finalizing_package :
                                R.string.preparing_ota_first_boot);
            }
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false);
            if (mCubeLayout) {
                setStatus(viewHolder, R.string.card_verifying, false);
                viewHolder.mProgressText.setText(null);
                viewHolder.mPercentage.setText(null);
            } else {
                viewHolder.mProgressText.setText(R.string.list_verifying_update);
            }
            viewHolder.mProgressBar.setIndeterminate(true);
        } else {
            canDelete = true;
            boolean failed = update.getStatus() == UpdateStatus.PAUSED_ERROR;
            setStatus(viewHolder, failed ? R.string.card_download_error : R.string.card_paused,
                    failed);
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId, !isBusy(),
                    failed ? R.string.action_try_again : 0);
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressText.setText(formatProgress(downloaded, total, null));
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }

        viewHolder.mMenu.setOnClickListener(getClickListener(update, canDelete, viewHolder.mMenu));
        viewHolder.mProgress.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
        if (viewHolder.mBuildSize != null) {
            viewHolder.mBuildSize.setVisibility(View.INVISIBLE);
        }
        setWhatsNewVisible(viewHolder, true);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        boolean whatsNew = true;
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true);
            setStatus(viewHolder, R.string.card_waiting_reboot, false);
            whatsNew = false;
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, true, viewHolder.mMenu));
            boolean canInstall = Utils.canInstall(update);
            boolean failed = update.getStatus() == UpdateStatus.INSTALLATION_FAILED;
            setButtonAction(viewHolder.mAction,
                    canInstall ? Action.INSTALL : Action.DELETE,
                    downloadId, !isBusy(), canInstall && failed ? R.string.action_try_again : 0);
            if (!canInstall) {
                setStatus(viewHolder, R.string.card_cannot_install, false);
                whatsNew = false;
            } else if (failed) {
                setStatus(viewHolder, R.string.card_install_failed, true);
            } else if (update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED) {
                setStatus(viewHolder, R.string.card_install_suspended, false);
            } else {
                setStatus(viewHolder, R.string.card_verified, false);
            }
        } else if (!Utils.canInstall(update)) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, !isBusy());
            setStatus(viewHolder, R.string.card_cannot_install, false);
            whatsNew = false;
        } else {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            boolean failed = update.getStatus() == UpdateStatus.VERIFICATION_FAILED;
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, !isBusy(),
                    failed ? R.string.action_try_again : 0);
            setStatus(viewHolder, failed ? R.string.card_verification_failed : 0, failed);
        }
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        if (viewHolder.mBuildSize != null) {
            viewHolder.mBuildSize.setText(fileSize);
            viewHolder.mBuildSize.setVisibility(View.VISIBLE);
        }

        if (mCubeLayout) {
            viewHolder.mProgress.setVisibility(View.GONE);
        } else {
            viewHolder.mProgress.setVisibility(View.INVISIBLE);
            viewHolder.mProgressText.setVisibility(View.INVISIBLE);
        }
        setWhatsNewVisible(viewHolder, whatsNew);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null) {
            viewHolder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // The update was deleted
            viewHolder.mAction.setEnabled(false);
            viewHolder.mAction.setText(mCubeLayout ? R.string.action_download_and_install
                    : R.string.action_download);
            return;
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown update status");
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildVersion = mActivity.getString(R.string.list_build_version,
                update.getVersion());
        if (mCubeLayout) {
            viewHolder.mBuildDate.setText(mActivity.getString(R.string.card_meta, buildDate,
                    Formatter.formatShortFileSize(mActivity, update.getFileSize())));
        } else {
            viewHolder.mBuildDate.setText(buildDate);
        }
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mBuildVersion.setCompoundDrawables(null, null, null, null);

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        notifyItemChanged(mDownloadIds.indexOf(downloadId));
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        mDownloadIds.remove(downloadId);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    /**
     * CubeOS: called when [downloadId] finished verifying. If it was started with "Download and
     * Install" on this screen, open the install confirmation (same checks as tapping Install).
     * Downloads that automatic updates install by themselves are left to them.
     */
    public void onUpdateVerified(String downloadId) {
        if (!mInstallWhenReady.remove(downloadId) || mUpdaterController == null) {
            return;
        }
        if (AutoUpdate.isEnabled(mActivity) && AutoUpdate.isAutomatic(mActivity, downloadId)) {
            return;
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null || !Utils.canInstall(update) || isBusy()
                || mActivity.isFinishing() || mActivity.isDestroyed()) {
            return;
        }
        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
        if (installDialog != null) {
            installDialog.show();
        }
    }

    private void startDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            startDownload(downloadId);
            return;
        }

        View checkboxView = LayoutInflater.from(mActivity).inflate(R.layout.checkbox_view, null);
        CheckBox checkbox = checkboxView.findViewById(R.id.checkbox);
        checkbox.setText(R.string.checkbox_metered_network_warning);

        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setView(checkboxView)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            if (checkbox.isChecked()) {
                                preferences.edit()
                                        .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, false)
                                        .apply();
                                mActivity.supportInvalidateOptionsMenu();
                            }
                            startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownload(String downloadId) {
        if (mCubeLayout) {
            mInstallWhenReady.add(downloadId);
        }
        mUpdaterController.startDownload(downloadId);
    }

    private void setButtonAction(Button button, Action action, final String downloadId,
            boolean enabled) {
        setButtonAction(button, action, downloadId, enabled, 0);
    }

    /** [label] replaces the action's usual text when not 0 (CubeOS: e.g. "Try Again"). */
    private void setButtonAction(Button button, Action action, final String downloadId,
            boolean enabled, @StringRes int label) {
        final View.OnClickListener clickListener;
        boolean primary = true;
        switch (action) {
            case DOWNLOAD:
                button.setText(mCubeLayout ? R.string.action_download_and_install
                        : R.string.action_download);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> startDownloadWithWarning(downloadId) : null;
                break;
            case PAUSE:
                primary = false;
                button.setText(R.string.action_pause);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId)
                        : null;
                break;
            case RESUME: {
                button.setText(R.string.action_resume);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        mUpdaterController.resumeDownload(downloadId);
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INSTALL: {
                button.setText(mCubeLayout ? R.string.action_install_now
                        : R.string.action_install);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update);
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                        if (installDialog != null) {
                            installDialog.show();
                        }
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INFO: {
                primary = false;
                button.setText(mCubeLayout ? R.string.action_details : R.string.action_info);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> showInfoDialog() : null;
            }
            break;
            case DELETE: {
                primary = false;
                button.setText(R.string.action_delete);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
            }
            break;
            case CANCEL_INSTALLATION: {
                primary = false;
                button.setText(R.string.action_cancel);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
            }
            break;
            case REBOOT: {
                button.setText(mCubeLayout ? R.string.action_restart_now : R.string.reboot);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    PowerManager pm = mActivity.getSystemService(PowerManager.class);
                    pm.reboot(null);
                } : null;
            }
            break;
            default:
                clickListener = null;
        }
        if (label != 0) {
            button.setText(label);
        }
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);
        if (mCubeLayout) {
            // Filled for the step that moves the update forward, tinted for pause/cancel/delete.
            button.setBackgroundResource(primary ? R.drawable.bg_pill_primary
                    : R.drawable.bg_pill_tonal);
            button.setTextColor(mActivity.getColor(primary ? R.color.cube_on_accent
                    : R.color.cube_accent));
        }

        // Disable action mode when a button is clicked
        button.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(mCubeLayout ? R.string.action_delete : android.R.string.ok,
                        (dialog, which) -> {
                            mInstallWhenReady.remove(downloadId);
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private View.OnClickListener getClickListener(final UpdateInfo update,
            final boolean canDelete, View anchor) {
        return view -> startActionMode(update, canDelete, anchor);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        boolean ab;
        try {
            ab = Utils.isABUpdate(update.getFile());
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        final String title;
        final String message;
        final int positive;
        if (mCubeLayout) {
            title = mActivity.getString(R.string.install_dialog_title,
                    mActivity.getString(R.string.list_build_version, update.getVersion()));
            message = mActivity.getString(ab ? R.string.install_dialog_message_ab
                    : R.string.install_dialog_message);
            positive = R.string.install_dialog_install;
        } else {
            String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                    DateFormat.MEDIUM, update.getTimestamp());
            String buildInfoText = mActivity.getString(R.string.list_build_version_date,
                    update.getVersion(), buildDate);
            title = mActivity.getString(R.string.apply_update_dialog_title);
            message = mActivity.getString(ab ? R.string.apply_update_dialog_message_ab
                    : R.string.apply_update_dialog_message, buildInfoText,
                    mActivity.getString(android.R.string.ok));
            positive = android.R.string.ok;
        }
        return new AlertDialog.Builder(mActivity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive,
                        (dialog, which) -> {
                            Utils.triggerUpdate(mActivity, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new AlertDialog.Builder(mActivity)
                .setMessage(mCubeLayout ? R.string.cancel_install_dialog_message
                        : R.string.cancel_installation_dialog_message)
                .setPositiveButton(mCubeLayout ? R.string.action_stop : android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(mCubeLayout ? R.string.cancel_install_keep
                        : android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    private void startActionMode(final UpdateInfo update, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();
        notifyItemChanged(update.getDownloadId());

        ContextThemeWrapper wrapper = new ContextThemeWrapper(mActivity,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.NO_GRAVITY,
                R.attr.actionOverflowMenuStyle, 0);
        popupMenu.inflate(R.menu.menu_action_mode);

        boolean shouldShowDelete = canDelete;
        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        if (isVerified && !Utils.canInstall(update) && !update.getAvailableOnline()) {
            shouldShowDelete = false;
        }
        MenuBuilder menu = (MenuBuilder) popupMenu.getMenu();
        menu.findItem(R.id.menu_delete_action).setVisible(shouldShowDelete);
        menu.findItem(R.id.menu_copy_url).setVisible(update.getAvailableOnline());
        menu.findItem(R.id.menu_export_update).setVisible(isVerified);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_delete_action) {
                getDeleteDialog(update.getDownloadId()).show();
                return true;
            } else if (itemId == R.id.menu_copy_url) {
                Utils.addToClipboard(mActivity,
                        mActivity.getString(R.string.label_download_url),
                        update.getDownloadUrl(),
                        mActivity.getString(R.string.toast_download_url_copied));
                return true;
            } else if (itemId == R.id.menu_export_update) {
                if (mActivity != null) {
                    mActivity.exportUpdate(update);
                }
                return true;
            }
            return false;
        });

        MenuPopupHelper helper = new MenuPopupHelper(wrapper, menu, anchor);
        helper.show();
    }

    private void showInfoDialog() {
        // CubeOS: plain words; the upstream message linked to the upstream project's wiki.
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.blocked_update_message)
                .show();
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }
}
