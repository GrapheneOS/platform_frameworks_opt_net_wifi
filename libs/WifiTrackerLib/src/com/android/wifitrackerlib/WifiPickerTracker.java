/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.wifitrackerlib.OsuWifiEntry.osuProviderToOsuWifiEntryKey;
import static com.android.wifitrackerlib.PasspointWifiEntry.uniqueIdToPasspointWifiEntryKey;
import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;
import static com.android.wifitrackerlib.Utils.mapScanResultsToKey;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTED;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTING;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_DISCONNECTED;
import static com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_UNREACHABLE;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wi-Fi tracker that provides all Wi-Fi related data to the Wi-Fi picker page.
 *
 * These include
 * - The connected WifiEntry
 * - List of all visible WifiEntries
 * - Number of saved networks
 * - Number of saved subscriptions
 */
public class WifiPickerTracker extends BaseWifiTracker {

    private static final String TAG = "WifiPickerTracker";

    private final WifiPickerTrackerCallback mListener;

    // Lock object for data returned by the public API
    private final Object mLock = new Object();
    // List representing return value of the getWifiEntries() API
    @GuardedBy("mLock") private final List<WifiEntry> mWifiEntries = new ArrayList<>();
    // Reference to the WifiEntry representing the network that is currently connected to
    private WifiEntry mConnectedWifiEntry;

    // Cache containing saved WifiConfigurations mapped by StandardWifiEntry key
    private final Map<String, WifiConfiguration> mWifiConfigCache = new HashMap<>();

    private final Map<String, WifiConfiguration> mSuggestedConfigCache = new HashMap<>();
    // Cache containing visible StandardWifiEntries. Must be accessed only by the worker thread.
    private final Map<String, StandardWifiEntry> mStandardWifiEntryCache = new HashMap<>();
    // Cache containing available suggested StandardWifiEntries. These entries may be already
    // represented in mStandardWifiEntryCache, so filtering must be done before they are returned in
    // getWifiEntry() and getConnectedWifiEntry().
    private final Map<String, StandardWifiEntry> mSuggestedWifiEntryCache = new HashMap<>();
    // Cache containing saved PasspointConfigurations mapped by PasspointWifiEntry key.
    private final Map<String, PasspointConfiguration> mPasspointConfigCache = new HashMap<>();
    // Cache containing visible PasspointWifiEntries. Must be accessed only by the worker thread.
    private final Map<String, PasspointWifiEntry> mPasspointWifiEntryCache = new HashMap<>();
    // Cache containing visible OsuWifiEntries. Must be accessed only by the worker thread.
    private final Map<String, OsuWifiEntry> mOsuWifiEntryCache = new HashMap<>();

    /**
     * Constructor for WifiPickerTracker.
     *
     * @param lifecycle Lifecycle this is tied to for lifecycle callbacks.
     * @param context Context for registering broadcast receiver and for resource strings.
     * @param wifiManager Provides all Wi-Fi info.
     * @param connectivityManager Provides network info.
     * @param networkScoreManager Provides network scores for network badging.
     * @param mainHandler Handler for processing listener callbacks.
     * @param workerHandler Handler for processing all broadcasts and running the Scanner.
     * @param clock Clock used for evaluating the age of scans
     * @param maxScanAgeMillis Max age for tracked WifiEntries.
     * @param scanIntervalMillis Interval between initiating scans.
     * @param listener WifiTrackerCallback listening on changes to WifiPickerTracker data.
     */
    public WifiPickerTracker(@NonNull Lifecycle lifecycle, @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            @Nullable WifiPickerTrackerCallback listener) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, listener,
                TAG);
        mListener = listener;
    }

    /**
     * Returns the WifiEntry representing the current connection.
     */
    @AnyThread
    public @Nullable WifiEntry getConnectedWifiEntry() {
        return mConnectedWifiEntry;
    }

    /**
     * Returns a list of in-range WifiEntries.
     *
     * The currently connected entry is omitted and may be accessed through
     * {@link #getConnectedWifiEntry()}
     */
    @AnyThread
    public @NonNull List<WifiEntry> getWifiEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mWifiEntries);
        }
    }

    /**
     * Returns the number of saved networks.
     */
    @AnyThread
    public int getNumSavedNetworks() {
        return mWifiConfigCache.size();
    }

    /**
     * Returns the number of saved subscriptions.
     */
    @AnyThread
    public int getNumSavedSubscriptions() {
        return mPasspointConfigCache.size();
    }

    @WorkerThread
    @Override
    protected void handleOnStart() {
        updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
        updateSuggestedWifiEntryConfigs(mWifiManager.getPrivilegedConfiguredNetworks().stream()
                .filter(config -> config.fromWifiNetworkSuggestion).collect(toList()));
        updatePasspointWifiEntryConfigs(mWifiManager.getPasspointConfigurations());
        mScanResultUpdater.update(mWifiManager.getScanResults());
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final NetworkInfo networkInfo = mConnectivityManager.getActiveNetworkInfo();
        updateConnectionInfo(wifiInfo, networkInfo);
        // Create a StandardWifiEntry for the current connection if there are no scan results yet.
        conditionallyCreateConnectedStandardWifiEntry(wifiInfo, networkInfo);
        conditionallyCreateConnectedSuggestedWifiEntry(wifiInfo, networkInfo);
        conditionallyCreateConnectedPasspointWifiEntry(wifiInfo, networkInfo);
        handleLinkPropertiesChanged(mConnectivityManager.getLinkProperties(
                mWifiManager.getCurrentNetwork()));
        notifyOnNumSavedNetworksChanged();
        notifyOnNumSavedSubscriptionsChanged();
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleScanResultsAvailableAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleConfiguredNetworksChangedAction(@NonNull Intent intent) {
        // TODO(b/70983952): Handle Passpoint configurations here
        checkNotNull(intent, "Intent cannot be null!");

        final WifiConfiguration config =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        if (config != null && !config.isPasspoint() && !config.fromWifiNetworkSuggestion) {
            updateStandardWifiEntryConfig(
                    config, (Integer) intent.getExtra(WifiManager.EXTRA_CHANGE_REASON));
        } else {
            updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
            updateSuggestedWifiEntryConfigs(mWifiManager.getPrivilegedConfiguredNetworks().stream()
                    .filter((privilegedConfig) -> privilegedConfig.fromWifiNetworkSuggestion)
                    .collect(toList()));
        }
        updatePasspointWifiEntryConfigs(mWifiManager.getPasspointConfigurations());
        // Update scans since config changes may result in different entries being shown.
        final List<ScanResult> scanResults = mScanResultUpdater.getScanResults();
        updateStandardWifiEntryScans(scanResults);
        updatePasspointWifiEntryScans(scanResults);
        updateOsuWifiEntryScans(scanResults);
        notifyOnNumSavedNetworksChanged();
        notifyOnNumSavedSubscriptionsChanged();
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleNetworkStateChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final NetworkInfo networkInfo =
                (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO);
        updateConnectionInfo(wifiInfo, networkInfo);
        // Create a StandardWifiEntry for the current connection if there are no scan results yet.
        conditionallyCreateConnectedStandardWifiEntry(wifiInfo, networkInfo);
        conditionallyCreateConnectedSuggestedWifiEntry(wifiInfo, networkInfo);
        conditionallyCreateConnectedPasspointWifiEntry(wifiInfo, networkInfo);
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleLinkPropertiesChanged(@Nullable LinkProperties linkProperties) {
        if (mConnectedWifiEntry != null
                && mConnectedWifiEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            mConnectedWifiEntry.updateLinkProperties(linkProperties);
        }
    }

    @WorkerThread
    @Override
    protected void handleNetworkCapabilitiesChanged(@Nullable NetworkCapabilities capabilities) {
        if (mConnectedWifiEntry != null
                && mConnectedWifiEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            mConnectedWifiEntry.updateNetworkCapabilities(capabilities);
        }
    }

    /**
     * Update the list returned by getWifiEntries() with the current states of the entry caches.
     */
    @WorkerThread
    private void updateWifiEntries() {
        synchronized (mLock) {
            mConnectedWifiEntry = mStandardWifiEntryCache.values().stream().filter(entry -> {
                final @WifiEntry.ConnectedState int connectedState = entry.getConnectedState();
                return connectedState == CONNECTED_STATE_CONNECTED
                        || connectedState == CONNECTED_STATE_CONNECTING;
            }).findAny().orElse(null /* other */);
            if (mConnectedWifiEntry == null) {
                mConnectedWifiEntry = mSuggestedWifiEntryCache.values().stream().filter(entry -> {
                    final @WifiEntry.ConnectedState int connectedState = entry.getConnectedState();
                    return connectedState == CONNECTED_STATE_CONNECTED
                            || connectedState == CONNECTED_STATE_CONNECTING;
                }).findAny().orElse(null /* other */);
            }
            if (mConnectedWifiEntry == null) {
                mConnectedWifiEntry = mPasspointWifiEntryCache.values().stream().filter(entry -> {
                    final @WifiEntry.ConnectedState int connectedState = entry.getConnectedState();
                    return connectedState == CONNECTED_STATE_CONNECTED
                            || connectedState == CONNECTED_STATE_CONNECTING;
                }).findAny().orElse(null /* other */);
            }
            if (mConnectedWifiEntry == null) {
                mConnectedWifiEntry = mOsuWifiEntryCache.values().stream().filter(entry -> {
                    final @WifiEntry.ConnectedState int connectedState = entry.getConnectedState();
                    return connectedState == CONNECTED_STATE_CONNECTED
                            || connectedState == CONNECTED_STATE_CONNECTING;
                }).findAny().orElse(null /* other */);
            }
            mWifiEntries.clear();
            for (String key : mStandardWifiEntryCache.keySet()) {
                // Continue if we're connected to this network with a non-user-shareable config.
                if (mConnectedWifiEntry != null
                        && TextUtils.equals(key, mConnectedWifiEntry.getKey())) {
                    continue;
                }
                StandardWifiEntry entry = mStandardWifiEntryCache.get(key);
                StandardWifiEntry suggestedEntry = mSuggestedWifiEntryCache.get(key);
                // Return a user-shareable suggested network to the user if one exists
                if (!entry.isSaved()
                        && suggestedEntry != null && suggestedEntry.isUserShareable()) {
                    if (suggestedEntry.getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
                        mWifiEntries.add(suggestedEntry);
                    }
                } else {
                    if (entry.getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
                        mWifiEntries.add(entry);
                    }
                }

            }
            mWifiEntries.addAll(mPasspointWifiEntryCache.values().stream().filter(entry ->
                    entry.getConnectedState() == CONNECTED_STATE_DISCONNECTED).collect(toList()));
            mWifiEntries.addAll(mOsuWifiEntryCache.values().stream().filter(entry ->
                    entry.getConnectedState() == CONNECTED_STATE_DISCONNECTED).collect(toList()));
            Collections.sort(mWifiEntries);
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Connected WifiEntry: " + mConnectedWifiEntry);
                Log.v(TAG, "Updated WifiEntries: " + Arrays.toString(mWifiEntries.toArray()));
            }
        }
        notifyOnWifiEntriesChanged();
    }

    /**
     * Updates or removes scan results for the corresponding StandardWifiEntries.
     * New entries will be created for scan results without an existing entry.
     * Unreachable entries will be removed.
     *
     * @param scanResults List of valid scan results to convey as StandardWifiEntries
     */
    @WorkerThread
    private void updateStandardWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        // Group scans by StandardWifiEntry key
        final Map<String, List<ScanResult>> scanResultsByKey = mapScanResultsToKey(
                scanResults,
                true /* chooseSingleSecurity */,
                mWifiConfigCache,
                mWifiManager.isWpa3SaeSupported(),
                mWifiManager.isWpa3SuiteBSupported(),
                mWifiManager.isEnhancedOpenSupported());

        // Iterate through current entries and update each entry's scan results
        mStandardWifiEntryCache.entrySet().removeIf(e -> {
            final String key = e.getKey();
            final StandardWifiEntry entry = e.getValue();
            // Update scan results if available, or set to null.
            entry.updateScanResultInfo(scanResultsByKey.remove(key));
            // Entry is now unreachable, remove it.
            return entry.getLevel() == WIFI_LEVEL_UNREACHABLE;
        });

        // Create new StandardWifiEntry objects for each leftover group of scan results.
        for (Map.Entry<String, List<ScanResult>> e: scanResultsByKey.entrySet()) {
            final StandardWifiEntry newEntry = new StandardWifiEntry(mContext, mMainHandler,
                    e.getKey(), e.getValue(), mWifiManager, false /* forSavedNetworksPage */);
            // Populate with a saved config, if available
            newEntry.updateConfig(mWifiConfigCache.get(newEntry.getKey()));
            mStandardWifiEntryCache.put(newEntry.getKey(), newEntry);
        }
    }

    /**
     * Updates or removes scan results for the corresponding StandardWifiEntries.
     * New entries will be created for scan results without an existing entry.
     * Unreachable entries will be removed.
     *
     * @param scanResults List of valid scan results to convey as StandardWifiEntries
     */
    @WorkerThread
    private void updateSuggestedWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        // Group scans by StandardWifiEntry key
        final Map<String, List<ScanResult>> scanResultsByKey = mapScanResultsToKey(
                scanResults,
                true /* chooseSingleSecurity */,
                mWifiConfigCache,
                mWifiManager.isWpa3SaeSupported(),
                mWifiManager.isWpa3SuiteBSupported(),
                mWifiManager.isEnhancedOpenSupported());

        Map<String, WifiConfiguration> userSharedConfigsByKey =
                mWifiManager.getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(scanResults)
                        .stream()
                        .collect(Collectors.toMap(
                                StandardWifiEntry::wifiConfigToStandardWifiEntryKey,
                                Function.identity()));

        Set<String> seenKeys = new TreeSet<>();
        for (String key : userSharedConfigsByKey.keySet()) {
            seenKeys.add(key);
            if (!mSuggestedWifiEntryCache.containsKey(key)) {
                mSuggestedWifiEntryCache.put(key, new StandardWifiEntry(mContext, mMainHandler, key,
                        userSharedConfigsByKey.get(key), mWifiManager,
                        false /* forSavedNetworksPage */));
            }
            final StandardWifiEntry entry = mSuggestedWifiEntryCache.get(key);
            entry.setUserShareable(true);
            entry.updateScanResultInfo(scanResultsByKey.get(key));
        }

        // Remove entries without configs
        mSuggestedWifiEntryCache.entrySet()
                .removeIf(entry -> {
                    StandardWifiEntry wifiEntry = entry.getValue();
                    String key = entry.getKey();
                    if (!seenKeys.contains(key)) {
                        wifiEntry.updateConfig(mSuggestedConfigCache.get(key));
                        wifiEntry.setUserShareable(false);
                    }
                    return !wifiEntry.isSaved();
                });
    }

    @WorkerThread
    private void updatePasspointWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        Set<String> seenKeys = new TreeSet<>();
        List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> matchingWifiConfigs =
                mWifiManager.getAllMatchingWifiConfigs(scanResults);
        for (Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> pair : matchingWifiConfigs) {
            final WifiConfiguration wifiConfig = pair.first;
            final List<ScanResult> homeScans =
                    pair.second.get(WifiManager.PASSPOINT_HOME_NETWORK);
            final List<ScanResult> roamingScans =
                    pair.second.get(WifiManager.PASSPOINT_ROAMING_NETWORK);
            final String key = uniqueIdToPasspointWifiEntryKey(wifiConfig.getKey());
            seenKeys.add(key);
            // Skip in case we don't have a Passpoint configuration for the returned unique key
            if (!mPasspointConfigCache.containsKey(key)) {
                continue;
            }

            // Create PasspointWifiEntry if one doesn't exist for the seen key yet.
            if (!mPasspointWifiEntryCache.containsKey(key)) {
                mPasspointWifiEntryCache.put(key, new PasspointWifiEntry(mContext,
                        mMainHandler, mPasspointConfigCache.get(key), mWifiManager,
                        false /* forSavedNetworksPage */));
            }
            mPasspointWifiEntryCache.get(key).updateScanResultInfo(wifiConfig,
                    homeScans, roamingScans);
        }

        // Remove entries that are now unreachable
        mPasspointWifiEntryCache.entrySet()
                .removeIf(entry -> entry.getValue().getLevel() == WIFI_LEVEL_UNREACHABLE
                        || !seenKeys.contains(entry.getKey()));
    }

    @WorkerThread
    private void updateOsuWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        Map<OsuProvider, List<ScanResult>> osuProviderToScans =
                mWifiManager.getMatchingOsuProviders(scanResults);
        Set<OsuProvider> alreadyProvisioned =
                mWifiManager.getMatchingPasspointConfigsForOsuProviders(osuProviderToScans.keySet())
                        .keySet();
        // Update each OsuWifiEntry with new scans (or empty scans).
        for (OsuWifiEntry entry : mOsuWifiEntryCache.values()) {
            entry.updateScanResultInfo(osuProviderToScans.remove(entry.getOsuProvider()));
        }

        // Create a new entry for each OsuProvider not already matched to an OsuWifiEntry
        for (OsuProvider provider : osuProviderToScans.keySet()) {
            OsuWifiEntry newEntry = new OsuWifiEntry(mContext, mMainHandler, provider, mWifiManager,
                    false /* forSavedNetworksPage */);
            newEntry.updateScanResultInfo(osuProviderToScans.get(provider));
            mOsuWifiEntryCache.put(osuProviderToOsuWifiEntryKey(provider), newEntry);
        }

        // Remove entries that are now unreachable or already provisioned
        mOsuWifiEntryCache.entrySet()
                .removeIf(entry -> entry.getValue().getLevel() == WIFI_LEVEL_UNREACHABLE
                        || alreadyProvisioned.contains(entry.getValue().getOsuProvider()));
    }

    /**
     * Conditionally updates the WifiEntry scan results based on the current wifi state and
     * whether the last scan succeeded or not.
     */
    @WorkerThread
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            updateStandardWifiEntryScans(Collections.emptyList());
            updateSuggestedWifiEntryScans(Collections.emptyList());
            updatePasspointWifiEntryScans(Collections.emptyList());
            updateOsuWifiEntryScans(Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            // Scan succeeded, cache new scans
            mScanResultUpdater.update(mWifiManager.getScanResults());
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }

        List<ScanResult> scanResults = mScanResultUpdater.getScanResults(scanAgeWindow);
        updateStandardWifiEntryScans(scanResults);
        updateSuggestedWifiEntryScans(scanResults);
        updatePasspointWifiEntryScans(scanResults);
        updateOsuWifiEntryScans(scanResults);
    }

    /**
     * Updates a single WifiConfiguration for the corresponding StandardWifiEntry if it exists.
     *
     * @param config WifiConfiguration to update
     * @param changeReason WifiManager.CHANGE_REASON_ADDED, WifiManager.CHANGE_REASON_REMOVED, or
     *                     WifiManager.CHANGE_REASON_CONFIG_CHANGE
     */
    @WorkerThread
    private void updateStandardWifiEntryConfig(@NonNull WifiConfiguration config,
            int changeReason) {
        checkNotNull(config, "Config should not be null!");

        final String key = wifiConfigToStandardWifiEntryKey(config);
        final StandardWifiEntry entry = mStandardWifiEntryCache.get(key);

        if (entry != null) {
            if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                mWifiConfigCache.remove(key);
            } else { // CHANGE_REASON_ADDED || CHANGE_REASON_CONFIG_CHANGE
                mWifiConfigCache.put(key, config);
            }
            entry.updateConfig(mWifiConfigCache.get(key));
        }
    }

    /**
     * Updates all saved WifiConfigurations for the corresponding StandardWifiEntries if they exist.
     *
     * @param configs List of saved WifiConfigurations
     */
    @WorkerThread
    private void updateStandardWifiEntryConfigs(@NonNull List<WifiConfiguration> configs) {
        checkNotNull(configs, "Config list should not be null!");
        mWifiConfigCache.clear();
        mWifiConfigCache.putAll(configs.stream().collect(Collectors.toMap(
                StandardWifiEntry::wifiConfigToStandardWifiEntryKey,
                Function.identity())));

        // Iterate through current entries and update each entry's config
        mStandardWifiEntryCache.entrySet().forEach((entry) -> {
            final StandardWifiEntry wifiEntry = entry.getValue();
            final String key = wifiEntry.getKey();
            wifiEntry.updateConfig(mWifiConfigCache.get(key));
        });
    }

    @WorkerThread
    private void updateSuggestedWifiEntryConfigs(@NonNull List<WifiConfiguration> configs) {
        checkNotNull(configs, "Config list should not be null!");
        mSuggestedConfigCache.clear();
        mSuggestedConfigCache.putAll(configs.stream().collect(Collectors.toMap(
                StandardWifiEntry::wifiConfigToStandardWifiEntryKey,
                Function.identity())));

        // Iterate through current entries and update each entry's config
        mSuggestedWifiEntryCache.entrySet().removeIf((entry) -> {
            final StandardWifiEntry wifiEntry = entry.getValue();
            final String key = wifiEntry.getKey();
            final WifiConfiguration cachedConfig = mSuggestedConfigCache.get(key);
            if (cachedConfig != null) {
                wifiEntry.updateConfig(cachedConfig);
                return false;
            } else {
                return true;
            }
        });
    }

    @WorkerThread
    private void updatePasspointWifiEntryConfigs(@NonNull List<PasspointConfiguration> configs) {
        checkNotNull(configs, "Config list should not be null!");
        mPasspointConfigCache.clear();
        mPasspointConfigCache.putAll(configs.stream().collect(
                toMap(config -> uniqueIdToPasspointWifiEntryKey(
                        config.getUniqueId()), Function.identity())));

        // Iterate through current entries and update each entry's config or remove if no config
        // matches the entry anymore.
        mPasspointWifiEntryCache.entrySet().removeIf((entry) -> {
            final PasspointWifiEntry wifiEntry = entry.getValue();
            final String key = wifiEntry.getKey();
            final PasspointConfiguration cachedConfig = mPasspointConfigCache.get(key);
            if (cachedConfig != null) {
                wifiEntry.updatePasspointConfig(cachedConfig);
                return false;
            } else {
                return true;
            }
        });
    }

    /**
     * Updates all WifiEntries with the current connection info.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void updateConnectionInfo(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        for (WifiEntry entry : mStandardWifiEntryCache.values()) {
            entry.updateConnectionInfo(wifiInfo, networkInfo);
        }
        for (WifiEntry entry : mSuggestedWifiEntryCache.values()) {
            entry.updateConnectionInfo(wifiInfo, networkInfo);
        }
        for (WifiEntry entry : mPasspointWifiEntryCache.values()) {
            entry.updateConnectionInfo(wifiInfo, networkInfo);
        }
        for (WifiEntry entry : mOsuWifiEntryCache.values()) {
            entry.updateConnectionInfo(wifiInfo, networkInfo);
        }
    }

    /**
     * Creates and caches a StandardWifiEntry representing the current connection using the current
     * WifiInfo and NetworkInfo if there are no scans results available for the network yet.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void conditionallyCreateConnectedStandardWifiEntry(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return;
        }

        final int connectedNetId = wifiInfo.getNetworkId();
        mWifiConfigCache.values().stream()
                .filter(config ->
                    config.networkId == connectedNetId && !mStandardWifiEntryCache.containsKey(
                        wifiConfigToStandardWifiEntryKey(config)))
                .findAny().ifPresent(config -> {
                    final StandardWifiEntry connectedEntry =
                            new StandardWifiEntry(mContext, mMainHandler,
                                    wifiConfigToStandardWifiEntryKey(config), config, mWifiManager,
                                    false /* forSavedNetworksPage */);
                    connectedEntry.updateConnectionInfo(wifiInfo, networkInfo);
                    mStandardWifiEntryCache.put(connectedEntry.getKey(), connectedEntry);
                });
    }

    /**
     * Creates and caches a suggested StandardWifiEntry representing the current connection using
     * the current WifiInfo and NetworkInfo if there are no scans results available for the network
     * yet.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void conditionallyCreateConnectedSuggestedWifiEntry(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return;
        }

        mSuggestedConfigCache.values().stream()
                .filter(config ->
                        TextUtils.equals(config.SSID, wifiInfo.getSSID())
                                && !mSuggestedWifiEntryCache.containsKey(
                                        wifiConfigToStandardWifiEntryKey(config)))
                .findAny().ifPresent(config -> {
                    final StandardWifiEntry connectedEntry =
                            new StandardWifiEntry(mContext, mMainHandler,
                                    wifiConfigToStandardWifiEntryKey(config), config, mWifiManager,
                                    false /* forSavedNetworksPage */);
                    connectedEntry.updateConnectionInfo(wifiInfo, networkInfo);
                    mSuggestedWifiEntryCache.put(connectedEntry.getKey(), connectedEntry);
                });
    }


    /**
     * Creates and caches a PasspointWifiEntry representing the current connection using the current
     * WifiInfo and NetworkInfo if there are no scans results available for the network yet.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void conditionallyCreateConnectedPasspointWifiEntry(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        if (!wifiInfo.isPasspointAp()) {
            return;
        }

        // TODO(b/148556276): This logic will match the fqdn of the connected passpoint network to
        // the first PasspointConfiguration found. This may or may not represent the actual
        // connection, so we will need to use WifiInfo.getPasspointUniqueId() once it is ready to be
        // a public API.
        final String connectedFqdn = wifiInfo.getPasspointFqdn();
        mPasspointConfigCache.values().stream()
                .filter(config ->
                        config.getHomeSp().getFqdn() == connectedFqdn
                                && !mPasspointWifiEntryCache.containsKey(
                                        uniqueIdToPasspointWifiEntryKey(config.getUniqueId())))
                .findAny().ifPresent(config -> {
                    final PasspointWifiEntry connectedEntry =
                            new PasspointWifiEntry(mContext, mMainHandler, config, mWifiManager,
                                    false /* forSavedNetworksPage */);
                    connectedEntry.updateConnectionInfo(wifiInfo, networkInfo);
                    mPasspointWifiEntryCache.put(connectedEntry.getKey(), connectedEntry);
                });
    }

    /**
     * Posts onWifiEntryChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiEntriesChanged);
        }
    }

    /**
     * Posts onNumSavedNetworksChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnNumSavedNetworksChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onNumSavedNetworksChanged);
        }
    }

    /**
     * Posts onNumSavedSubscriptionsChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnNumSavedSubscriptionsChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onNumSavedSubscriptionsChanged);
        }
    }

    /**
     * Listener for changes to the list of visible WifiEntries as well as the number of saved
     * networks and subscriptions.
     *
     * These callbacks must be run on the MainThread.
     */
    public interface WifiPickerTrackerCallback extends BaseWifiTracker.BaseWifiTrackerCallback {
        /**
         * Called when there are changes to
         *      {@link #getConnectedWifiEntry()}
         *      {@link #getWifiEntries()}
         */
        @MainThread
        void onWifiEntriesChanged();

        /**
         * Called when there are changes to
         *      {@link #getNumSavedNetworks()}
         */
        @MainThread
        void onNumSavedNetworksChanged();

        /**
         * Called when there are changes to
         *      {@link #getNumSavedSubscriptions()}
         */
        @MainThread
        void onNumSavedSubscriptionsChanged();
    }
}
