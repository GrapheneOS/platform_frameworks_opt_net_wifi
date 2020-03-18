/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.content.IntentFilter;
import android.net.MacAddress;
import android.net.util.MacAddressUtils;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiMigration;
import android.os.Handler;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Random;

import javax.annotation.Nullable;
import javax.crypto.Mac;

/**
 * Provides API for reading/writing soft access point configuration.
 */
public class WifiApConfigStore {

    // Intent when user has interacted with the softap settings change notification
    public static final String ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT =
            "com.android.server.wifi.WifiApConfigStoreUtil.HOTSPOT_CONFIG_USER_TAPPED_CONTENT";

    private static final String TAG = "WifiApConfigStore";

    // Note: This is the legacy Softap config file. This is only used for migrating data out
    // of this file on first reboot.
    private static final String LEGACY_AP_CONFIG_FILE = "softap.conf";

    @VisibleForTesting
    public static final int AP_CONFIG_FILE_VERSION = 3;

    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;

    @VisibleForTesting
    static final int SSID_MIN_LEN = 1;
    @VisibleForTesting
    static final int SSID_MAX_LEN = 32;
    @VisibleForTesting
    static final int PSK_MIN_LEN = 8;
    @VisibleForTesting
    static final int PSK_MAX_LEN = 63;

    private SoftApConfiguration mPersistentWifiApConfig = null;

    private final Context mContext;
    private final Handler mHandler;
    private final BackupManagerProxy mBackupManagerProxy;
    private final MacAddressUtil mMacAddressUtil;
    private final Mac mMac;
    private final WifiConfigManager mWifiConfigManager;
    private final ActiveModeWarden mActiveModeWarden;
    private boolean mHasNewDataToSerialize = false;

    /**
     * Module to interact with the wifi config store.
     */
    private class SoftApStoreDataSource implements SoftApStoreData.DataSource {

        public SoftApConfiguration toSerialize() {
            mHasNewDataToSerialize = false;
            return mPersistentWifiApConfig;
        }

        public void fromDeserialized(SoftApConfiguration config) {
            mPersistentWifiApConfig = new SoftApConfiguration.Builder(config).build();
        }

        public void reset() {
            if (mPersistentWifiApConfig != null) {
                // Note: Reset is invoked when WifiConfigStore.read() is invoked on boot completed.
                // If we had migrated data from the legacy store before that (which is most likely
                // true because we read the legacy file in the constructor here, whereas
                // WifiConfigStore.read() is only triggered on boot completed), trigger a write to
                // persist the migrated data.
                mHandler.post(() -> mWifiConfigManager.saveToStore(true));
            }
        }

        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    WifiApConfigStore(Context context, WifiInjector wifiInjector, Handler handler,
            BackupManagerProxy backupManagerProxy, WifiConfigStore wifiConfigStore,
            WifiConfigManager wifiConfigManager, ActiveModeWarden activeModeWarden) {
        this(context, wifiInjector, handler, backupManagerProxy, wifiConfigStore,
                wifiConfigManager, activeModeWarden,
                WifiMigration.convertAndRetrieveSharedConfigStoreFile(
                        WifiMigration.STORE_FILE_SHARED_SOFTAP));
    }

    WifiApConfigStore(Context context,
            WifiInjector wifiInjector,
            Handler handler,
            BackupManagerProxy backupManagerProxy,
            WifiConfigStore wifiConfigStore,
            WifiConfigManager wifiConfigManager,
            ActiveModeWarden activeModeWarden,
            InputStream legacyApConfigFileStream) {
        mContext = context;
        mHandler = handler;
        mBackupManagerProxy = backupManagerProxy;
        mWifiConfigManager = wifiConfigManager;
        mActiveModeWarden = activeModeWarden;

        // One time migration from legacy config store.
        // TODO (b/149418926): softap migration needs to be fixed. Move the logic
        // below to WifiMigration. This is to allow OEM's who have been supporting some new AOSP R
        // features like blocklist/allowlist in Q and stored the data using the old key/value
        // format.
        if (legacyApConfigFileStream != null) {
            /* Load AP configuration from persistent storage. */
            SoftApConfiguration config =
                    loadApConfigurationFromLegacyFile(legacyApConfigFileStream);
            if (config != null) {
                // Persist in the new store.
                persistConfigAndTriggerBackupManagerProxy(config);
                Log.i(TAG, "Migrated data out of legacy store file");
                WifiMigration.removeSharedConfigStoreFile(
                        WifiMigration.STORE_FILE_SHARED_SOFTAP);
            }
        }

        // Register store data listener
        wifiConfigStore.registerStoreData(
                wifiInjector.makeSoftApStoreData(new SoftApStoreDataSource()));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT);
        mMacAddressUtil = wifiInjector.getMacAddressUtil();
        mMac = mMacAddressUtil.obtainMacRandHashFunctionForSap(Process.WIFI_UID);
        if (mMac == null) {
            Log.wtf(TAG, "Failed to obtain secret for SAP MAC randomization."
                    + " All randomized MAC addresses are lost!");
        }
    }

    /**
     * Return the current soft access point configuration.
     */
    public synchronized SoftApConfiguration getApConfiguration() {
        if (mPersistentWifiApConfig == null) {
            /* Use default configuration. */
            Log.d(TAG, "Fallback to use default AP configuration");
            persistConfigAndTriggerBackupManagerProxy(getDefaultApConfiguration());
        }
        SoftApConfiguration sanitizedPersistentconfig =
                sanitizePersistentApConfig(mPersistentWifiApConfig);
        if (mPersistentWifiApConfig != sanitizedPersistentconfig) {
            Log.d(TAG, "persisted config was converted, need to resave it");
            persistConfigAndTriggerBackupManagerProxy(sanitizedPersistentconfig);
        }
        return mPersistentWifiApConfig;
    }

    /**
     * Update the current soft access point configuration.
     * Restore to default AP configuration if null is provided.
     * This can be invoked under context of binder threads (WifiManager.setWifiApConfiguration)
     * and the main Wifi thread (CMD_START_AP).
     */
    public synchronized void setApConfiguration(SoftApConfiguration config) {
        if (config == null) {
            config = getDefaultApConfiguration();
        } else {
            config = sanitizePersistentApConfig(config);
        }
        persistConfigAndTriggerBackupManagerProxy(config);
    }

    /**
     * Returns SoftApConfiguration in which some parameters might be reset to supported default
     * config.
     *
     * MaxNumberOfClients and setClientControlByUserEnabled will need HAL support client force
     * disconnect. Reset to default when device doesn't support it.
     *
     * SAE/SAE-Transition need hardware support, reset to secured WPA2 security type when device
     * doesn't support it.
     */
    public SoftApConfiguration resetToDefaultForUnsupportedConfig(
            @NonNull SoftApConfiguration config) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
        if (!ApConfigUtil.isClientForceDisconnectSupported(mContext)) {
            configBuilder.setMaxNumberOfClients(0);
            configBuilder.setClientControlByUserEnabled(false);
            if (config.getMaxNumberOfClients() != 0) {
                Log.e(TAG, "Reset MaxNumberOfClients to 0 due to device doesn't support");
            }
            if (config.isClientControlByUserEnabled()) {
                Log.e(TAG, "Reset ClientControlByUser to false due to device doesn't support");
            }
        }

        if (!ApConfigUtil.isWpa3SaeSupported(mContext) && (config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                || config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)) {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            Log.e(TAG, "Device doesn't support WPA3-SAE, reset config to WPA2");
        }

        return configBuilder.build();
    }

    private SoftApConfiguration sanitizePersistentApConfig(SoftApConfiguration config) {
        SoftApConfiguration.Builder convertedConfigBuilder = null;

        // Persistent config may not set BSSID.
        if (config.getBssid() != null) {
            convertedConfigBuilder = new SoftApConfiguration.Builder(config);
            convertedConfigBuilder.setBssid(null);
        }

        // some countries are unable to support 5GHz only operation, always allow for 2GHz when
        // config doesn't force channel
        if (config.getChannel() == 0 && (config.getBand() & SoftApConfiguration.BAND_2GHZ) == 0) {
            Log.w(TAG, "Supplied ap config band without 2.4G, add allowing for 2.4GHz");
            if (convertedConfigBuilder == null) {
                convertedConfigBuilder = new SoftApConfiguration.Builder(config);
            }
            convertedConfigBuilder.setBand(config.getBand() | SoftApConfiguration.BAND_2GHZ);
        }
        return convertedConfigBuilder == null ? config : convertedConfigBuilder.build();
    }

    private void persistConfigAndTriggerBackupManagerProxy(SoftApConfiguration config) {
        mPersistentWifiApConfig = config;
        mHasNewDataToSerialize = true;
        mWifiConfigManager.saveToStore(true);
        mBackupManagerProxy.notifyDataChanged();
    }

    /**
     * Load AP configuration from legacy persistent storage.
     * Note: This is deprecated and only used for migrating data once on reboot.
     */
    private static SoftApConfiguration loadApConfigurationFromLegacyFile(InputStream fis) {
        SoftApConfiguration config = null;
        DataInputStream in = null;
        try {
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
            in = new DataInputStream(new BufferedInputStream(fis));

            int version = in.readInt();
            if (version < 1 || version > AP_CONFIG_FILE_VERSION) {
                Log.e(TAG, "Bad version on hotspot configuration file");
                return null;
            }
            configBuilder.setSsid(in.readUTF());

            if (version >= 2) {
                int band = in.readInt();
                int channel = in.readInt();

                if (channel == 0) {
                    configBuilder.setBand(
                            ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(band));
                } else {
                    configBuilder.setChannel(channel,
                            ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(band));
                }
            }

            if (version >= 3) {
                configBuilder.setHiddenSsid(in.readBoolean());
            }

            int authType = in.readInt();
            if (authType == WifiConfiguration.KeyMgmt.WPA2_PSK) {
                configBuilder.setPassphrase(in.readUTF(),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            }
            config = configBuilder.build();
        } catch (IOException e) {
            Log.e(TAG, "Error reading hotspot configuration " + e);
            config = null;
        } catch (IllegalArgumentException ie) {
            Log.e(TAG, "Invalid hotspot configuration " + ie);
            config = null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing hotspot configuration during read" + e);
                }
            }
        }
        return config;
    }

    /**
     * Generate a default WPA3 SAE transition (if supported) or WPA2 based
     * configuration with a random password.
     * We are changing the Wifi Ap configuration storage from secure settings to a
     * flat file accessible only by the system. A WPA2 based default configuration
     * will keep the device secure after the update.
     */
    private SoftApConfiguration getDefaultApConfiguration() {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setBand(SoftApConfiguration.BAND_2GHZ);
        configBuilder.setSsid(mContext.getResources().getString(
                R.string.wifi_tether_configure_ssid_default) + "_" + getRandomIntForDefaultSsid());
        if (ApConfigUtil.isWpa3SaeSupported(mContext)) {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
        } else {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        }
        return configBuilder.build();
    }

    private static int getRandomIntForDefaultSsid() {
        Random random = new Random();
        return random.nextInt((RAND_SSID_INT_MAX - RAND_SSID_INT_MIN) + 1) + RAND_SSID_INT_MIN;
    }

    private static String generateLohsSsid(Context context) {
        return context.getResources().getString(
                R.string.wifi_localhotspot_configure_ssid_default) + "_"
                + getRandomIntForDefaultSsid();
    }

    /**
     * Generate a temporary WPA2 based configuration for use by the local only hotspot.
     * This config is not persisted and will not be stored by the WifiApConfigStore.
     */
    public static SoftApConfiguration generateLocalOnlyHotspotConfig(Context context, int apBand,
            @Nullable SoftApConfiguration customConfig) {
        SoftApConfiguration.Builder configBuilder;
        if (customConfig != null) {
            configBuilder = new SoftApConfiguration.Builder(customConfig);
        } else {
            configBuilder = new SoftApConfiguration.Builder();
        }

        configBuilder.setBand(apBand);

        if (customConfig == null || customConfig.getSsid() == null) {
            configBuilder.setSsid(generateLohsSsid(context));
        }
        if (customConfig == null) {
            if (ApConfigUtil.isWpa3SaeSupported(context)) {
                configBuilder.setPassphrase(generatePassword(),
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
            } else {
                configBuilder.setPassphrase(generatePassword(),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            }
        }

        return configBuilder.build();
    }

    /**
     * @return a copy of the given SoftApConfig with the BSSID randomized, unless a custom BSSID is
     * already set.
     */
    SoftApConfiguration randomizeBssidIfUnset(Context context, SoftApConfiguration config) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
        if (config.getBssid() == null && context.getResources().getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported)) {
            MacAddress macAddress = mMacAddressUtil.calculatePersistentMac(config.getSsid(), mMac);
            if (macAddress == null) {
                Log.e(TAG, "Failed to calculate MAC from SSID. "
                        + "Generating new random MAC instead.");
                macAddress = MacAddressUtils.createRandomUnicastAddress();
            }
            configBuilder.setBssid(macAddress);
        }
        return configBuilder.build();
    }

    /**
     * Verify provided SSID for existence, length and conversion to bytes
     *
     * @param ssid String ssid name
     * @return boolean indicating ssid met requirements
     */
    private static boolean validateApConfigSsid(String ssid) {
        if (TextUtils.isEmpty(ssid)) {
            Log.d(TAG, "SSID for softap configuration must be set.");
            return false;
        }

        try {
            byte[] ssid_bytes = ssid.getBytes(StandardCharsets.UTF_8);

            if (ssid_bytes.length < SSID_MIN_LEN || ssid_bytes.length > SSID_MAX_LEN) {
                Log.d(TAG, "softap SSID is defined as UTF-8 and it must be at least "
                        + SSID_MIN_LEN + " byte and not more than " + SSID_MAX_LEN + " bytes");
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap config SSID verification failed: malformed string " + ssid);
            return false;
        }
        return true;
    }

    /**
     * Verify provided preSharedKey in ap config for WPA2_PSK network meets requirements.
     */
    private static boolean validateApConfigPreSharedKey(String preSharedKey) {
        if (preSharedKey.length() < PSK_MIN_LEN || preSharedKey.length() > PSK_MAX_LEN) {
            Log.d(TAG, "softap network password string size must be at least " + PSK_MIN_LEN
                    + " and no more than " + PSK_MAX_LEN);
            return false;
        }

        try {
            preSharedKey.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap network password verification failed: malformed string");
            return false;
        }
        return true;
    }

    /**
     * Validate a SoftApConfiguration is properly configured for use by SoftApManager.
     *
     * This method checks the length of the SSID and for sanity between security settings (if it
     * requires a password, was one provided?).
     *
     * @param apConfig {@link SoftApConfiguration} to use for softap mode
     * @return boolean true if the provided config meets the minimum set of details, false
     * otherwise.
     */
    static boolean validateApWifiConfiguration(@NonNull SoftApConfiguration apConfig) {
        // first check the SSID
        if (!validateApConfigSsid(apConfig.getSsid())) {
            // failed SSID verificiation checks
            return false;
        }

        String preSharedKey = apConfig.getPassphrase();
        boolean hasPreSharedKey = !TextUtils.isEmpty(preSharedKey);
        int authType;

        try {
            authType = apConfig.getSecurityType();
        } catch (IllegalStateException e) {
            Log.d(TAG, "Unable to get AuthType for softap config: " + e.getMessage());
            return false;
        }

        if (authType == SoftApConfiguration.SECURITY_TYPE_OPEN) {
            // open networks should not have a password
            if (hasPreSharedKey) {
                Log.d(TAG, "open softap network should not have a password");
                return false;
            }
        } else if (authType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                || authType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION
                || authType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) {
            // this is a config that should have a password - check that first
            if (!hasPreSharedKey) {
                Log.d(TAG, "softap network password must be set");
                return false;
            }
            if (authType != SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                    && !validateApConfigPreSharedKey(preSharedKey)) {
                // failed preSharedKey checks for WPA2 and WPA3 SAE Transition mode.
                return false;
            }
        } else {
            // this is not a supported security type
            Log.d(TAG, "softap configs must either be open or WPA2 PSK networks");
            return false;
        }

        return true;
    }

    private static String generatePassword() {
        // Characters that will be used for password generation. Some characters commonly known to
        // be confusing like 0 and O excluded from this list.
        final String allowed = "23456789abcdefghijkmnpqrstuvwxyz";
        final int passLength = 15;

        StringBuilder sb = new StringBuilder(passLength);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < passLength; i++) {
            sb.append(allowed.charAt(random.nextInt(allowed.length())));
        }
        return sb.toString();
    }
}
