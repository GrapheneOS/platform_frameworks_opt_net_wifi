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

import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;

import android.content.Context;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.OsuProvider;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;

/**
 * WifiEntry representation of an Online Sign-up entry, uniquely identified by FQDN.
 */
class OsuWifiEntry extends WifiEntry {
    static final String KEY_PREFIX = "OsuWifiEntry:";

    @NonNull private final List<ScanResult> mCurrentScanResults = new ArrayList<>();

    @NonNull private final String mKey;
    @NonNull private String mFriendlyName;
    @NonNull private final Context mContext;
    @NonNull private OsuProvider mOsuProvider;

    private int mLevel = WIFI_LEVEL_UNREACHABLE;

    /**
     * Create n OsuWifiEntry with the associated OsuProvider
     */
    OsuWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull OsuProvider osuProvider,
            @NonNull WifiManager wifiManager) throws IllegalArgumentException {
        super(callbackHandler, wifiManager, false /* forSavedNetworksPage */);

        checkNotNull(osuProvider, "Cannot construct with null osuProvider!");

        mContext = context;
        mOsuProvider = osuProvider;
        mKey = osuProviderToOsuWifiEntryKey(osuProvider);
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    public String getTitle() {
        return mOsuProvider.getFriendlyName();
    }

    @Override
    public String getSummary() {
        return getSummary(true /* concise */);
    }

    @Override
    public String getSummary(boolean concise) {
        // TODO(b/70983952): Fill this method in
        return "Osu (Placeholder Text)"; // Placeholder string
    }

    @Override
    public int getLevel() {
        return mLevel;
    }

    @Override
    public String getSsid() {
        // TODO(b/70983952): Fill this method in in case we need the SSID for verbose logging
        return "";
    }

    @Override
    @Security
    public int getSecurity() {
        return SECURITY_NONE;
    }

    @Override
    public String getMacAddress() {
        // TODO(b/70983952): Fill this method in in case we need the mac address for verbose logging
        return null;
    }

    @Override
    public boolean isMetered() {
        return false;
    }

    @Override
    public boolean isSaved() {
        return false;
    }

    @Override
    public boolean isSubscription() {
        return false;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        return null;
    }

    @Override
    public boolean canConnect() {
        return mLevel != WIFI_LEVEL_UNREACHABLE
                && getConnectedState() == CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        // TODO(b/70983952): Fill this method in.
    }

    // Exiting from the OSU flow should disconnect from the network.
    @Override
    public boolean canDisconnect() {
        return false;
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
    }

    @Override
    public boolean canForget() {
        return false;
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
    }

    @Override
    public boolean canSignIn() {
        return false;
    }

    @Override
    public void signIn(@Nullable SignInCallback callback) {
        return;
    }

    @Override
    public boolean canShare() {
        return false;
    }

    @Override
    public boolean canEasyConnect() {
        return false;
    }

    @Override
    public String getQrCodeString() {
        return null;
    }

    @Override
    public boolean canSetPassword() {
        return false;
    }

    @Override
    public void setPassword(@NonNull String password) {
        // Do nothing.
    }

    @Override
    @MeteredChoice
    public int getMeteredChoice() {
        // Metered choice is meaningless for OSU entries
        return METERED_CHOICE_AUTO;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return false;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        // Metered choice is meaningless for OSU entries
    }

    @Override
    @Privacy
    public int getPrivacy() {
        // MAC Randomization choice is meaningless for OSU entries.
        return PRIVACY_UNKNOWN;
    }

    @Override
    public boolean canSetPrivacy() {
        return false;
    }

    @Override
    public void setPrivacy(int privacy) {
        // MAC Randomization choice is meaningless for OSU entries.
    }

    @Override
    public boolean isAutoJoinEnabled() {
        return false;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return false;
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
    }

    @Override
    public String getSecurityString(boolean concise) {
        return "";
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults == null) scanResults = new ArrayList<>();

        mCurrentScanResults.clear();
        mCurrentScanResults.addAll(scanResults);

        final ScanResult bestScanResult = getBestScanResultByLevel(mCurrentScanResults);
        if (bestScanResult == null) {
            mLevel = WIFI_LEVEL_UNREACHABLE;
        } else {
            mLevel = mWifiManager.calculateSignalLevel(bestScanResult.level);
        }

        notifyOnUpdated();
    }

    @NonNull
    static String osuProviderToOsuWifiEntryKey(@NonNull OsuProvider osuProvider) {
        checkNotNull(osuProvider, "Cannot create key with null OsuProvider!");
        return KEY_PREFIX + osuProvider.getFriendlyName() + ","
                + osuProvider.getServerUri().toString();
    }

    @WorkerThread
    @Override
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        // TODO(b/70983952): Fill this method in.
        return false;
    }

    @Override
    String getScanResultDescription() {
        // TODO(b/70983952): Fill this method in.
        return "";
    }
}
