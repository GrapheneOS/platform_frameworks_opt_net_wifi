/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.os.Build.VERSION_CODES;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.SharedConnectivityManager;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.List;
import java.util.Objects;

/**
 * WifiEntry representation of a Known Network provided via {@link SharedConnectivityManager}.
 */
@TargetApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
public class KnownNetworkEntry extends StandardWifiEntry{
    static final String TAG = "KnownNetworkEntry";
    public static final String KEY_PREFIX = "KnownNetworkEntry:";

    @Nullable private final SharedConnectivityManager mSharedConnectivityManager;
    @NonNull private final KnownNetwork mKnownNetworkData;

    KnownNetworkEntry(
            @NonNull WifiTrackerInjector injector, @NonNull Context context,
            @NonNull Handler callbackHandler, @NonNull StandardWifiEntryKey key,
            @NonNull WifiManager wifiManager, boolean forSavedNetworksPage,
            @Nullable SharedConnectivityManager sharedConnectivityManager,
            @NonNull KnownNetwork knownNetworkData) {
        super(injector, context, callbackHandler, key, wifiManager, forSavedNetworksPage);
        mSharedConnectivityManager = sharedConnectivityManager;
        mKnownNetworkData = knownNetworkData;
    }

    KnownNetworkEntry(
            @NonNull WifiTrackerInjector injector, @NonNull Context context,
            @NonNull Handler callbackHandler, @NonNull StandardWifiEntryKey key,
            @Nullable List<WifiConfiguration> configs, @Nullable List<ScanResult> scanResults,
            @NonNull WifiManager wifiManager, boolean forSavedNetworksPage,
            @Nullable SharedConnectivityManager sharedConnectivityManager,
            @NonNull KnownNetwork knownNetworkData) throws IllegalArgumentException {
        super(injector, context, callbackHandler, key, configs, scanResults, wifiManager,
                forSavedNetworksPage);
        mSharedConnectivityManager = sharedConnectivityManager;
        mKnownNetworkData = knownNetworkData;
    }

    @Override
    public synchronized String getSummary(boolean concise) {
        if (isSaved()) {
            return super.getSummary(concise);
        }
        return "Known"; // TODO(b/271869550): Fully implement this WIP string.
    }

    @Override
    public synchronized void connect(@Nullable ConnectCallback callback) {
        if (isSaved()) {
            super.connect(callback);
        } else if (mSharedConnectivityManager != null) {
            mSharedConnectivityManager.connectKnownNetwork(mKnownNetworkData);
        }
        // TODO(b/271907257): Integrate data from connection status updates
    }

    @Override
    public synchronized void forget(@Nullable ForgetCallback callback) {
        if (mSharedConnectivityManager != null) {
            mSharedConnectivityManager.forgetKnownNetwork(mKnownNetworkData);
        }
        super.forget(callback);
        // TODO(b/271907257): Integrate data from connection status updates
    }

    @WorkerThread
    protected synchronized boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return false;
        }
        return Objects.equals(getStandardWifiEntryKey().getScanResultKey(),
                ssidAndSecurityTypeToStandardWifiEntryKey(WifiInfo.sanitizeSsid(wifiInfo.getSSID()),
                        wifiInfo.getCurrentSecurityType()).getScanResultKey());
    }

    @Override
    public String getKey() {
        return KEY_PREFIX + super.getKey();
    }
}
