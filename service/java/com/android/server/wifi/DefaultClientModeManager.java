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

package com.android.server.wifi;

import static android.net.wifi.nl80211.WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_UNKNOWN;

import android.annotation.NonNull;
import android.net.DhcpResultsParcelable;
import android.net.Network;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.IBinder;
import android.os.WorkSource;

import com.android.server.wifi.util.ActionListenerWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This is used for creating a public {ClientModeManager} instance when wifi is off.
 */
public class DefaultClientModeManager implements ClientModeManager {
    @Override
    public void start() { }

    @Override
    public void stop() { }

    @Override
    public boolean isStopping() {
        return false;
    }

    @Override
    public int getRole() {
        return 0;
    }

    @Override
    public void setRole(int role) { }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) { }

    @Override
    public void connectNetwork(NetworkUpdateResult result, ActionListenerWrapper wrapper,
            int callingUid) {
        // wifi off, can't connect.
        wrapper.sendFailure(WifiManager.BUSY);
    }

    @Override
    public void saveNetwork(NetworkUpdateResult result, ActionListenerWrapper wrapper,
            int callingUid) {
        // wifi off, nothing more to do here.
        wrapper.sendSuccess();
    }

    @Override
    public void disconnect() { }

    @Override
    public void reconnect(WorkSource ws) { }

    @Override
    public void reassociate() { }

    @Override
    public void startConnectToNetwork(int networkId, int uid, String bssid) { }

    @Override
    public void startRoamToNetwork(int networkId, ScanResult scanResult) { }

    @Override
    public WifiScoreReport getWifiScoreReport() {
        return null;
    }

    @Override
    public void resetSimAuthNetworks(@ClientModeImpl.ResetSimReason int resetReason) { }

    @Override
    public void sendBluetoothAdapterConnectionStateChange(int state) { }

    @Override
    public void sendBluetoothAdapterStateChange(int state) { }

    @Override
    public void handleBootCompleted() { }

    @Override
    public int syncGetWifiState() {
        return WifiManager.WIFI_STATE_DISABLED;
    }

    @Override
    public WifiLinkLayerStats syncGetLinkLayerStats() {
        return null;
    }

    @Override
    public WifiInfo syncRequestConnectionInfo() {
        return null;
    }

    @Override
    public boolean syncQueryPasspointIcon(long bssid, String fileName) {
        return false;
    }

    @Override
    public Network syncGetCurrentNetwork() {
        return null;
    }

    @Override
    public DhcpResultsParcelable syncGetDhcpResultsParcelable() {
        return null;
    }

    @Override
    public String syncGetWifiStateByName() {
        return null;
    }

    public long syncGetSupportedFeatures() {
        return 0L;
    }

    @Override
    public boolean syncStartSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        return false;
    }

    @Override
    public boolean isWifiStandardSupported(@WifiAnnotations.WifiStandard int standard) {
        return false;
    }

    @Override
    public void enableTdls(String remoteMacAddress, boolean enable) { }

    @Override
    public void removeNetworkRequestUserApprovedAccessPointsForApp(@NonNull String packageName) { }

    @Override
    public void clearNetworkRequestUserApprovedAccessPoints() { }

    @Override
    public void addNetworkRequestMatchCallback(IBinder binder,
            INetworkRequestMatchCallback callback,
            int callbackIdentifier) { }

    @Override
    public void removeNetworkRequestMatchCallback(int callbackIdentifier) { }

    @Override
    public void dumpIpClient(FileDescriptor fd, PrintWriter pw, String[] args) { }

    @Override
    public void updateLinkLayerStatsRssiAndScoreReport() { }

    @Override
    public void enableVerboseLogging(int verbose) { }

    @Override
    public String getFactoryMacAddress() {
        return null;
    }

    @Override
    public WifiConfiguration getCurrentWifiConfiguration() {
        return null;
    }

    @Override
    public WifiLinkLayerStats getWifiLinkLayerStats() {
        return null;
    }

    @Override
    public boolean setPowerSave(boolean ps) {
        return false;
    }

    @Override
    public boolean setLowLatencyMode(boolean enabled) {
        return false;
    }

    @Override
    public WifiMulticastLockManager.FilterController getMcastLockManagerFilterController() {
        return null;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isDisconnected() {
        return true;
    }

    @Override
    public boolean isSupplicantTransientState() {
        return false;
    }

    @Override
    public boolean getIpReachabilityDisconnectEnabled() {
        return false;
    }

    @Override
    public void setIpReachabilityDisconnectEnabled(boolean enabled) { }

    @Override
    public int getPollRssiIntervalMsecs() {
        return -1;
    }

    @Override
    public void setPollRssiIntervalMsecs(int newPollIntervalMsecs) { }

    @Override
    public void setNetworkRequestUserApprovedApp(@NonNull String packageName, boolean approved) { }

    @Override
    public boolean hasNetworkRequestUserApprovedApp(@NonNull String packageName) {
        return false;
    }

    @Override
    public void probeLink(WifiNl80211Manager.SendMgmtFrameCallback callback, int mcs) {
        callback.onFailure(SEND_MGMT_FRAME_ERROR_UNKNOWN);
    }
}
