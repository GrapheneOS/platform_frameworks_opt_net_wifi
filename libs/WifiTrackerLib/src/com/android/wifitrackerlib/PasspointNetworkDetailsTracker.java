/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wifitrackerlib;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.PasspointWifiEntry.uniqueIdToPasspointWifiEntryKey;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTED;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Implementation of NetworkDetailsTracker that tracks a single PasspointWifiEntry.
 */
class PasspointNetworkDetailsTracker extends NetworkDetailsTracker {
    private static final String TAG = "PasspointNetworkDetailsTracker";

    private final PasspointWifiEntry mChosenEntry;

    PasspointNetworkDetailsTracker(@NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String key) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, TAG);

        PasspointConfiguration passpointConfig = mWifiManager.getPasspointConfigurations().stream()
                .filter(config -> TextUtils.equals(
                        uniqueIdToPasspointWifiEntryKey(config.getUniqueId()), key))
                .findAny().get();

        checkNotNull(passpointConfig,
                "Cannot find PasspointConfiguration with matching unique identifier: "
                        + passpointConfig.getUniqueId());

        mChosenEntry = new PasspointWifiEntry(mContext, mMainHandler, passpointConfig,
                mWifiManager, false /* forSavedNetworksPage */);
        cacheNewScanResults();
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        conditionallyUpdateConfig();
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final NetworkInfo networkInfo = mConnectivityManager.getActiveNetworkInfo();
        mChosenEntry.updateConnectionInfo(wifiInfo, networkInfo);
        handleLinkPropertiesChanged(mConnectivityManager.getLinkProperties(
                mWifiManager.getCurrentNetwork()));
    }

    @AnyThread
    @Override
    @NonNull
    public WifiEntry getWifiEntry() {
        return mChosenEntry;
    }

    @WorkerThread
    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
    }

    @WorkerThread
    @Override
    protected void handleScanResultsAvailableAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
    }

    @WorkerThread
    @Override
    protected void handleConfiguredNetworksChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateConfig();
    }

    @WorkerThread
    @Override
    protected void handleNetworkStateChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        mChosenEntry.updateConnectionInfo(mWifiManager.getConnectionInfo(),
                (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO));
    }

    @WorkerThread
    @Override
    protected void handleLinkPropertiesChanged(@NonNull LinkProperties linkProperties) {
        if (mChosenEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            mChosenEntry.updateLinkProperties(linkProperties);
        }
    }

    @WorkerThread
    private void updatePasspointWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> matchingWifiConfigs =
                mWifiManager.getAllMatchingWifiConfigs(scanResults);
        for (Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> pair : matchingWifiConfigs) {
            final WifiConfiguration wifiConfig = pair.first;
            final String key = uniqueIdToPasspointWifiEntryKey(wifiConfig.getKey());

            if (TextUtils.equals(key, mChosenEntry.getKey())) {
                mChosenEntry.updateScanResultInfo(wifiConfig,
                        pair.second.get(WifiManager.PASSPOINT_HOME_NETWORK),
                        pair.second.get(WifiManager.PASSPOINT_ROAMING_NETWORK));
                return;
            }
        }
        // No AP in range; set scan results and connection config to null.
        mChosenEntry.updateScanResultInfo(null /* wifiConfig */,
                null /* homeScanResults */,
                null /* roamingScanResults */);
    }

    /**
     * Updates the tracked entry's scan results up to the max scan age (or more, if the last scan
     * was unsuccessful). If Wifi is disabled, the tracked entry's level will be cleared.
     */
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            mChosenEntry.updateScanResultInfo(null /* wifiConfig */,
                    Collections.emptyList(), Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            cacheNewScanResults();
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }

        updatePasspointWifiEntryScans(mScanResultUpdater.getScanResults(scanAgeWindow));
    }

    /**
     * Updates the tracked entry's PasspointConfiguration from getPasspointConfigurations()
     */
    private void conditionallyUpdateConfig() {
        mWifiManager.getPasspointConfigurations().stream()
                .filter(config -> TextUtils.equals(
                        uniqueIdToPasspointWifiEntryKey(config.getUniqueId()),
                        mChosenEntry.getKey()))
                .findAny().ifPresent(config -> mChosenEntry.updatePasspointConfig(config));
    }

    /**
     * Updates ScanResultUpdater with new ScanResults.
     */
    private void cacheNewScanResults() {
        mScanResultUpdater.update(mWifiManager.getScanResults());
    }
}
