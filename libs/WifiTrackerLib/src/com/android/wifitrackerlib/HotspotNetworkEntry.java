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
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.sharedconnectivity.app.HotspotNetwork;
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo;
import android.net.wifi.sharedconnectivity.app.SharedConnectivityManager;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * WifiEntry representation of a Hotspot Network provided via {@link SharedConnectivityManager}.
 */
@TargetApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
public class HotspotNetworkEntry extends WifiEntry {
    static final String TAG = "HotspotNetworkEntry";
    public static final String KEY_PREFIX = "HotspotNetworkEntry:";

    @NonNull private final WifiTrackerInjector mInjector;
    @NonNull private final Context mContext;
    @Nullable private final SharedConnectivityManager mSharedConnectivityManager;

    @Nullable private HotspotNetwork mHotspotNetworkData;
    @NonNull private HotspotNetworkEntryKey mKey;

    /**
     * If editing this IntDef also edit the definition in:
     * {@link android.net.wifi.sharedconnectivity.app.HotspotNetwork}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            HotspotNetwork.NETWORK_TYPE_UNKNOWN,
            HotspotNetwork.NETWORK_TYPE_CELLULAR,
            HotspotNetwork.NETWORK_TYPE_WIFI,
            HotspotNetwork.NETWORK_TYPE_ETHERNET
    })
    public @interface NetworkType {} // TODO(b/271868642): Add IfThisThanThat lint

    /**
     * If editing this IntDef also edit the definition in:
     * {@link android.net.wifi.sharedconnectivity.app.NetworkProviderInfo}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NetworkProviderInfo.DEVICE_TYPE_UNKNOWN,
            NetworkProviderInfo.DEVICE_TYPE_PHONE,
            NetworkProviderInfo.DEVICE_TYPE_TABLET,
            NetworkProviderInfo.DEVICE_TYPE_LAPTOP,
            NetworkProviderInfo.DEVICE_TYPE_WATCH,
            NetworkProviderInfo.DEVICE_TYPE_AUTO
    })
    public @interface DeviceType {} // TODO(b/271868642): Add IfThisThanThat lint

    /**
     * Create a HotspotNetworkEntry from HotspotNetwork data.
     */
    HotspotNetworkEntry(
            @NonNull WifiTrackerInjector injector,
            @NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull WifiManager wifiManager,
            @Nullable SharedConnectivityManager sharedConnectivityManager,
            @NonNull HotspotNetwork hotspotNetworkData) {
        super(callbackHandler, wifiManager, false /*forSavedNetworksPage*/);
        mInjector = injector;
        mContext = context;
        mSharedConnectivityManager = sharedConnectivityManager;
        mHotspotNetworkData = hotspotNetworkData;
        mKey = new HotspotNetworkEntryKey(hotspotNetworkData);
    }

    /**
     * Create a HotspotNetworkEntry from HotspotNetworkEntryKey.
     */
    HotspotNetworkEntry(
            @NonNull WifiTrackerInjector injector,
            @NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull WifiManager wifiManager,
            @Nullable SharedConnectivityManager sharedConnectivityManager,
            @NonNull HotspotNetworkEntryKey key) {
        super(callbackHandler, wifiManager, false /*forSavedNetworksPage*/);
        mInjector = injector;
        mContext = context;
        mSharedConnectivityManager = sharedConnectivityManager;
        mHotspotNetworkData = null;
        mKey = key;
    }

    @Override
    public String getKey() {
        return mKey.toString();
    }

    public HotspotNetworkEntryKey getHotspotNetworkEntryKey() {
        return mKey;
    }

    /**
     * Updates the hotspot data for this entry. Creates a new key when called.
     *
     * @param hotspotNetworkData An updated data set from SharedConnectivityService.
     */
    @WorkerThread
    protected synchronized void updateHotspotNetworkData(
            @NonNull HotspotNetwork hotspotNetworkData) {
        mHotspotNetworkData = hotspotNetworkData;
        mKey = new HotspotNetworkEntryKey(hotspotNetworkData);
        notifyOnUpdated();
    }

    @WorkerThread
    protected synchronized boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        return Objects.equals(mKey.getBssid(), wifiInfo.getBSSID());
    }

    @Override
    public String getTitle() {
        if (mHotspotNetworkData == null) {
            return "";
        }
        return mHotspotNetworkData.getNetworkProviderInfo().getDeviceName();
    }

    @Override
    public String getSummary(boolean concise) {
        if (mHotspotNetworkData == null) {
            return "";
        }
        // TODO(b/271869550): Fully implement this WIP string.
        return mHotspotNetworkData.getNetworkName() + " from "
                + mHotspotNetworkData.getNetworkProviderInfo().getModelName();
    }

    @Override
    public int getLevel() {
        if (mHotspotNetworkData == null) {
            return 0;
        }
        return mHotspotNetworkData.getNetworkProviderInfo().getConnectionStrength();
    }

    /**
     * Alternate summary string to be used on Network & internet page.
     *
     * @return Display string.
     */
    public String getAlternateSummary() {
        if (mHotspotNetworkData == null) {
            return "";
        }
        // TODO(b/271869550): Fully implement this WIP string.
        return mHotspotNetworkData.getNetworkName() + " from "
                + mHotspotNetworkData.getNetworkProviderInfo().getDeviceName();
    }

    /**
     * Network type used by the host device to connect to the internet.
     *
     * @return NetworkType enum.
     */
    @NetworkType
    public int getNetworkType() {
        if (mHotspotNetworkData == null) {
            return HotspotNetwork.NETWORK_TYPE_UNKNOWN;
        }
        return mHotspotNetworkData.getHostNetworkType();
    }

    /**
     * Device type of the host device.
     *
     * @return DeviceType enum.
     */
    @DeviceType
    public int getDeviceType() {
        if (mHotspotNetworkData == null) {
            return NetworkProviderInfo.DEVICE_TYPE_UNKNOWN;
        }
        return mHotspotNetworkData.getNetworkProviderInfo().getDeviceType();
    }

    @Override
    public boolean canConnect() {
        return getConnectedState() == CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        if (mSharedConnectivityManager != null) {
            mSharedConnectivityManager.connectHotspotNetwork(mHotspotNetworkData);
        }
        // TODO(b/271907257): Integrate data from connection status updates
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
        if (mSharedConnectivityManager != null) {
            mSharedConnectivityManager.disconnectHotspotNetwork(mHotspotNetworkData);
        }
        // TODO(b/271907257): Integrate data from connection status updates
    }

    @Nullable
    public HotspotNetwork getHotspotNetworkData() {
        return mHotspotNetworkData;
    }

    static class HotspotNetworkEntryKey {
        private static final String KEY_IS_VIRTUAL_ENTRY_KEY = "IS_VIRTUAL_ENTRY_KEY";
        private static final String KEY_DEVICE_ID_KEY = "DEVICE_ID_KEY";
        private static final String KEY_BSSID_KEY = "BSSID_KEY";
        private static final String KEY_SCAN_RESULT_KEY = "SCAN_RESULT_KEY";

        private boolean mIsVirtualEntry;
        private long mDeviceId;
        @Nullable
        private String mBssid;
        @Nullable
        private StandardWifiEntry.ScanResultKey mScanResultKey;

        /**
         * Creates a HotspotNetworkEntryKey based on a {@link HotspotNetwork} parcelable object.
         *
         * @param hotspotNetworkData A {@link HotspotNetwork} object from SharedConnectivityService.
         */
        HotspotNetworkEntryKey(@NonNull HotspotNetwork hotspotNetworkData) {
            mDeviceId = hotspotNetworkData.getDeviceId();
            if (hotspotNetworkData.getHotspotSsid() == null
                    || (hotspotNetworkData.getHotspotBssid() == null)
                    || (hotspotNetworkData.getHotspotSecurityTypes() == null)) {
                mIsVirtualEntry = true;
                mBssid = null;
                mScanResultKey = null;
            } else {
                mIsVirtualEntry = false;
                mBssid = hotspotNetworkData.getHotspotBssid();
                mScanResultKey = new StandardWifiEntry.ScanResultKey(
                        hotspotNetworkData.getHotspotSsid(),
                        new ArrayList<>(hotspotNetworkData.getHotspotSecurityTypes()));
            }
        }

        /**
         * Creates a HotspotNetworkEntryKey from its String representation.
         */
        HotspotNetworkEntryKey(@NonNull String string) {
            mScanResultKey = null;
            if (!string.startsWith(KEY_PREFIX)) {
                Log.e(TAG, "String key does not start with key prefix!");
                return;
            }
            try {
                final JSONObject keyJson = new JSONObject(
                        string.substring(KEY_PREFIX.length()));
                if (keyJson.has(KEY_IS_VIRTUAL_ENTRY_KEY)) {
                    mIsVirtualEntry = keyJson.getBoolean(KEY_IS_VIRTUAL_ENTRY_KEY);
                }
                if (keyJson.has(KEY_DEVICE_ID_KEY)) {
                    mDeviceId = keyJson.getLong(KEY_DEVICE_ID_KEY);
                }
                if (keyJson.has(KEY_BSSID_KEY)) {
                    mBssid = keyJson.getString(KEY_BSSID_KEY);
                }
                if (keyJson.has(KEY_SCAN_RESULT_KEY)) {
                    mScanResultKey = new StandardWifiEntry.ScanResultKey(keyJson.getString(
                            KEY_SCAN_RESULT_KEY));
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSONException while converting HotspotNetworkEntryKey to string: " + e);
            }
        }

        /**
         * Returns the JSON String representation of this HotspotNetworkEntryKey.
         */
        @Override
        public String toString() {
            final JSONObject keyJson = new JSONObject();
            try {
                keyJson.put(KEY_IS_VIRTUAL_ENTRY_KEY, mIsVirtualEntry);
                keyJson.put(KEY_DEVICE_ID_KEY, mDeviceId);
                if (mBssid != null) {
                    keyJson.put(KEY_BSSID_KEY, mBssid);
                }
                if (mScanResultKey != null) {
                    keyJson.put(KEY_SCAN_RESULT_KEY, mScanResultKey.toString());
                }
            } catch (JSONException e) {
                Log.wtf(TAG,
                        "JSONException while converting HotspotNetworkEntryKey to string: " + e);
            }
            return KEY_PREFIX + keyJson.toString();
        }

        public boolean isVirtualEntry() {
            return mIsVirtualEntry;
        }

        public long getDeviceId() {
            return mDeviceId;
        }

        /**
         * Returns the BSSID of this HotspotNetworkEntryKey to match against wifiInfo
         */
        @Nullable
        String getBssid() {
            return mBssid;
        }

        /**
         * Returns the ScanResultKey of this HotspotNetworkEntryKey to match against ScanResults
         */
        @Nullable
        StandardWifiEntry.ScanResultKey getScanResultKey() {
            return mScanResultKey;
        }
    }
}
