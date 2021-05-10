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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_NO_CREDENTIALS;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;
import static android.net.wifi.WifiInfo.sanitizeSsid;

import static com.android.wifitrackerlib.Utils.getAppLabel;
import static com.android.wifitrackerlib.Utils.getAutoConnectDescription;
import static com.android.wifitrackerlib.Utils.getAverageSpeedFromScanResults;
import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getCarrierNameForSubId;
import static com.android.wifitrackerlib.Utils.getCurrentNetworkCapabilitiesInformation;
import static com.android.wifitrackerlib.Utils.getDisconnectedStateDescription;
import static com.android.wifitrackerlib.Utils.getImsiProtectionDescription;
import static com.android.wifitrackerlib.Utils.getMeteredDescription;
import static com.android.wifitrackerlib.Utils.getNetworkDetailedState;
import static com.android.wifitrackerlib.Utils.getSecurityTypeFromWifiConfiguration;
import static com.android.wifitrackerlib.Utils.getSecurityTypeFromWifiInfo;
import static com.android.wifitrackerlib.Utils.getSecurityTypesFromScanResult;
import static com.android.wifitrackerlib.Utils.getSpeedDescription;
import static com.android.wifitrackerlib.Utils.getSpeedFromWifiInfo;
import static com.android.wifitrackerlib.Utils.getSubIdForConfig;
import static com.android.wifitrackerlib.Utils.getVerboseLoggingDescription;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * WifiEntry representation of a logical Wi-Fi network, uniquely identified by SSID and security.
 *
 * This type of WifiEntry can represent both open and saved networks.
 */
@VisibleForTesting
public class StandardWifiEntry extends WifiEntry {
    static final String TAG = "StandardWifiEntry";
    public static final String KEY_PREFIX = "StandardWifiEntry:";


    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            EAP_WPA,
            EAP_WPA2_WPA3,
            EAP_UNKNOWN
    })

    public @interface EapType {}

    private static final int EAP_WPA = 0;       // WPA-EAP
    private static final int EAP_WPA2_WPA3 = 1; // RSN-EAP
    private static final int EAP_UNKNOWN = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PSK_WPA,
            PSK_WPA2,
            PSK_WPA_WPA2,
            PSK_UNKNOWN
    })

    public @interface PskType {}

    private static final int PSK_WPA = 0;
    private static final int PSK_WPA2 = 1;
    private static final int PSK_WPA_WPA2 = 2;
    private static final int PSK_UNKNOWN = 3;

    @NonNull private final StandardWifiEntryKey mKey;

    @NonNull private final Context mContext;

    private final Object mLock = new Object();
    // Map of security type to matching scan results
    @NonNull private final Map<Integer, List<ScanResult>> mMatchingScanResults = new HashMap<>();
    // Map of security type to matching WifiConfiguration
    // TODO: Change this to single WifiConfiguration once we can get multiple security type configs.
    @NonNull private final Map<Integer, WifiConfiguration> mMatchingWifiConfigs = new HashMap<>();

    // List of the target scan results to be displayed. This should match the highest available
    // security from all of the matched WifiConfigurations.
    // If no WifiConfigurations are available, then these should match the most appropriate security
    // type (e.g. PSK for an PSK/SAE entry, OWE for an Open/OWE entry).
    // Note: Must be thread safe for generating the verbose scan summary
    @GuardedBy("mLock")
    @NonNull private final List<ScanResult> mTargetScanResults = new ArrayList<>();
    // Target WifiConfiguration for connection and displaying WifiConfiguration info
    private WifiConfiguration mTargetWifiConfig;
    private @Security int mTargetSecurity = SECURITY_NONE;
    private @EapType int mEapType = EAP_UNKNOWN;
    private @PskType int mPskType = PSK_UNKNOWN;

    private boolean mIsUserShareable = false;
    @Nullable private String mRecommendationServiceLabel;

    private boolean mShouldAutoOpenCaptivePortal = false;

    private final boolean mIsWpa3SaeSupported;
    private final boolean mIsWpa3SuiteBSupported;
    private final boolean mIsEnhancedOpenSupported;

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull StandardWifiEntryKey key, @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) {
        super(callbackHandler, wifiManager, scoreCache, forSavedNetworksPage);
        mContext = context;
        mKey = key;
        mTargetSecurity = mKey.getScanResultKey().getSecurityTypes().stream()
                .sorted()
                .findFirst()
                .get();
        mIsWpa3SaeSupported = wifiManager.isWpa3SaeSupported();
        mIsWpa3SuiteBSupported = wifiManager.isWpa3SuiteBSupported();
        mIsEnhancedOpenSupported = wifiManager.isEnhancedOpenSupported();
        updateRecommendationServiceLabel();
    }

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull StandardWifiEntryKey key,
            @Nullable List<WifiConfiguration> configs,
            @Nullable List<ScanResult> scanResults,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        this(context, callbackHandler, key, wifiManager, scoreCache,
                forSavedNetworksPage);
        if (configs != null && !configs.isEmpty()) {
            updateConfig(configs);
        }
        if (scanResults != null && !scanResults.isEmpty()) {
            updateScanResultInfo(scanResults);
        }
    }

    @Override
    public String getKey() {
        return mKey.toString();
    }

    StandardWifiEntryKey getStandardWifiEntryKey() {
        return mKey;
    }

    @Override
    public String getTitle() {
        return mKey.getScanResultKey().getSsid();
    }

    @Override
    public String getSummary(boolean concise) {
        StringJoiner sj = new StringJoiner(mContext.getString(
                R.string.wifitrackerlib_summary_separator));

        if (!concise && mForSavedNetworksPage && mTargetWifiConfig != null) {
            final CharSequence appLabel = getAppLabel(mContext, mTargetWifiConfig.creatorName);
            if (!TextUtils.isEmpty(appLabel)) {
                sj.add(mContext.getString(R.string.wifitrackerlib_saved_network, appLabel));
            }
        }

        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            String disconnectDescription = getDisconnectedStateDescription(mContext, this);
            if (TextUtils.isEmpty(disconnectDescription)) {
                if (concise) {
                    sj.add(mContext.getString(R.string.wifitrackerlib_wifi_disconnected));
                } else if (!mForSavedNetworksPage) {
                    // Summary for unconnected suggested network
                    if (isSuggestion()) {
                        String carrierName = getCarrierNameForSubId(mContext,
                                getSubIdForConfig(mContext, mTargetWifiConfig));
                        String suggestorName = getAppLabel(mContext, mTargetWifiConfig.creatorName);
                        if (TextUtils.isEmpty(suggestorName)) {
                            // Fall-back to the package name in case the app label is missing
                            suggestorName = mTargetWifiConfig.creatorName;
                        }
                        sj.add(mContext.getString(R.string.wifitrackerlib_available_via_app,
                                carrierName != null ? carrierName : suggestorName));
                    } else if (isSaved()) {
                        sj.add(mContext.getString(R.string.wifitrackerlib_wifi_remembered));
                    }
                }
            } else {
                sj.add(disconnectDescription);
            }
        } else {
            final String connectDescription = getConnectStateDescription();
            if (!TextUtils.isEmpty(connectDescription)) {
                sj.add(connectDescription);
            }
        }

        final String speedDescription = getSpeedDescription(mContext, this);
        if (!TextUtils.isEmpty(speedDescription)) {
            sj.add(speedDescription);
        }

        final String autoConnectDescription = getAutoConnectDescription(mContext, this);
        if (!TextUtils.isEmpty(autoConnectDescription)) {
            sj.add(autoConnectDescription);
        }

        final String meteredDescription = getMeteredDescription(mContext, this);
        if (!TextUtils.isEmpty(meteredDescription)) {
            sj.add(meteredDescription);
        }

        if (!concise) {
            final String verboseLoggingDescription = getVerboseLoggingDescription(this);
            if (!TextUtils.isEmpty(verboseLoggingDescription)) {
                sj.add(verboseLoggingDescription);
            }
        }

        return sj.toString();
    }

    private String getConnectStateDescription() {
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            // For suggestion or specifier networks
            final String suggestionOrSpecifierPackageName = mWifiInfo != null
                    ? mWifiInfo.getRequestingPackageName() : null;
            if (!TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                String carrierName = mTargetWifiConfig != null
                        ? getCarrierNameForSubId(
                                mContext, getSubIdForConfig(mContext, mTargetWifiConfig))
                        : null;
                String suggestorName = getAppLabel(mContext, suggestionOrSpecifierPackageName);
                if (TextUtils.isEmpty(suggestorName)) {
                    // Fall-back to the package name in case the app label is missing
                    suggestorName = suggestionOrSpecifierPackageName;
                }
                return mContext.getString(R.string.wifitrackerlib_connected_via_app,
                        carrierName != null ? carrierName : suggestorName);
            }

            if (!isSaved() && !isSuggestion()) {
                // Special case for connected + ephemeral networks.
                if (!TextUtils.isEmpty(mRecommendationServiceLabel)) {
                    return String.format(mContext.getString(
                            R.string.wifitrackerlib_connected_via_network_scorer),
                            mRecommendationServiceLabel);
                }
                return mContext.getString(
                        R.string.wifitrackerlib_connected_via_network_scorer_default);
            }

            if (mIsLowQuality) {
                return mContext.getString(R.string.wifi_connected_low_quality);
            }

            String networkCapabilitiesinformation =
                    getCurrentNetworkCapabilitiesInformation(mContext,  mNetworkCapabilities);
            if (!TextUtils.isEmpty(networkCapabilitiesinformation)) {
                return networkCapabilitiesinformation;
            }
        }

        return getNetworkDetailedState(mContext, mNetworkInfo);
    }

    @Override
    public CharSequence getSecondSummary() {
        return getConnectedState() == CONNECTED_STATE_CONNECTED
                ? getImsiProtectionDescription(mContext, getWifiConfiguration()) : "";
    }

    @Override
    public String getSsid() {
        return mKey.getScanResultKey().getSsid();
    }

    @Override
    @Security
    public int getSecurity() {
        return mTargetSecurity;
    }

    @Override
    public String getMacAddress() {
        if (mWifiInfo != null) {
            final String wifiInfoMac = mWifiInfo.getMacAddress();
            if (!TextUtils.isEmpty(wifiInfoMac)
                    && !TextUtils.equals(wifiInfoMac, DEFAULT_MAC_ADDRESS)) {
                return wifiInfoMac;
            }
        }
        if (mTargetWifiConfig == null || getPrivacy() != PRIVACY_RANDOMIZED_MAC) {
            final String[] factoryMacs = mWifiManager.getFactoryMacAddresses();
            if (factoryMacs.length > 0) {
                return factoryMacs[0];
            }
            return null;
        }
        return mTargetWifiConfig.getRandomizedMacAddress().toString();
    }

    @Override
    public boolean isMetered() {
        return getMeteredChoice() == METERED_CHOICE_METERED
                || (mTargetWifiConfig != null && mTargetWifiConfig.meteredHint);
    }

    @Override
    public boolean isSaved() {
        return mTargetWifiConfig != null && !mTargetWifiConfig.fromWifiNetworkSuggestion
                && !mTargetWifiConfig.isEphemeral();
    }

    @Override
    public boolean isSuggestion() {
        return mTargetWifiConfig != null && mTargetWifiConfig.fromWifiNetworkSuggestion;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        if (!isSaved()) {
            return null;
        }
        return mTargetWifiConfig;
    }

    @Override
    public ConnectedInfo getConnectedInfo() {
        return mConnectedInfo;
    }

    @Override
    public boolean canConnect() {
        if (mLevel == WIFI_LEVEL_UNREACHABLE
                || getConnectedState() != CONNECTED_STATE_DISCONNECTED) {
            return false;
        }
        // Allow connection for EAP SIM dependent methods if the SIM of specified carrier ID is
        // active in the device.
        if (mTargetSecurity == SECURITY_EAP && mTargetWifiConfig != null
                && mTargetWifiConfig.enterpriseConfig != null) {
            if (!mTargetWifiConfig.enterpriseConfig.isAuthenticationSimBased()) {
                return true;
            }
            List<SubscriptionInfo> activeSubscriptionInfos = ((SubscriptionManager) mContext
                    .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE))
                    .getActiveSubscriptionInfoList();
            if (activeSubscriptionInfos == null || activeSubscriptionInfos.size() == 0) {
                return false;
            }
            if (mTargetWifiConfig.carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
                // To connect via default subscription.
                return true;
            }
            for (SubscriptionInfo subscriptionInfo : activeSubscriptionInfos) {
                if (subscriptionInfo.getCarrierId() == mTargetWifiConfig.carrierId) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        mConnectCallback = callback;
        // We should flag this network to auto-open captive portal since this method represents
        // the user manually connecting to a network (i.e. not auto-join).
        mShouldAutoOpenCaptivePortal = true;
        mWifiManager.stopRestrictingAutoJoinToSubscriptionId();
        if (isSaved() || isSuggestion()) {
            if (Utils.isSimCredential(mTargetWifiConfig)
                    && !Utils.isSimPresent(mContext, mTargetWifiConfig.carrierId)) {
                if (callback != null) {
                    mCallbackHandler.post(() ->
                            callback.onConnectResult(
                                    ConnectCallback.CONNECT_STATUS_FAILURE_SIM_ABSENT));
                }
                return;
            }
            // Saved/suggested network
            mWifiManager.connect(mTargetWifiConfig.networkId, new ConnectActionListener());
        } else {
            // Unsaved network
            if (mTargetSecurity == SECURITY_NONE
                    || mTargetSecurity == SECURITY_OWE) {
                // Open network
                final WifiConfiguration connectConfig = new WifiConfiguration();
                connectConfig.SSID = "\"" + mKey.getScanResultKey().getSsid() + "\"";

                if (mTargetSecurity == SECURITY_OWE) {
                    // Use OWE if possible
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                    connectConfig.requirePmf = true;
                } else {
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                }
                mWifiManager.connect(connectConfig, new ConnectActionListener());
            } else {
                // Secure network
                if (callback != null) {
                    mCallbackHandler.post(() ->
                            callback.onConnectResult(
                                    ConnectCallback.CONNECT_STATUS_FAILURE_NO_CONFIG));
                }
            }
        }
    }

    @Override
    public boolean canDisconnect() {
        return getConnectedState() == CONNECTED_STATE_CONNECTED;
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
        if (canDisconnect()) {
            mCalledDisconnect = true;
            mDisconnectCallback = callback;
            mCallbackHandler.postDelayed(() -> {
                if (callback != null && mCalledDisconnect) {
                    callback.onDisconnectResult(
                            DisconnectCallback.DISCONNECT_STATUS_FAILURE_UNKNOWN);
                }
            }, 10_000 /* delayMillis */);
            mWifiManager.disableEphemeralNetwork("\"" + mKey.getScanResultKey().getSsid() + "\"");
            mWifiManager.disconnect();
        }
    }

    @Override
    public boolean canForget() {
        return getWifiConfiguration() != null;
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
        if (canForget()) {
            mForgetCallback = callback;
            mWifiManager.forget(mTargetWifiConfig.networkId, new ForgetActionListener());
        }
    }

    @Override
    public boolean canSignIn() {
        return mNetworkCapabilities != null
                && mNetworkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    @Override
    public void signIn(@Nullable SignInCallback callback) {
        if (canSignIn()) {
            // canSignIn() implies that this WifiEntry is the currently connected network, so use
            // getCurrentNetwork() to start the captive portal app.
            ((ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .startCaptivePortalApp(mWifiManager.getCurrentNetwork());
        }
    }

    /**
     * Returns whether the network can be shared via QR code.
     * See https://github.com/zxing/zxing/wiki/Barcode-Contents#wi-fi-network-config-android-ios-11
     */
    @Override
    public boolean canShare() {
        if (getWifiConfiguration() == null) {
            return false;
        }

        switch (mTargetSecurity) {
            case SECURITY_NONE:
            case SECURITY_OWE:
            case SECURITY_WEP:
            case SECURITY_PSK:
            case SECURITY_SAE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether the user can use Easy Connect to onboard a device to the network.
     * See https://www.wi-fi.org/discover-wi-fi/wi-fi-easy-connect
     */
    @Override
    public boolean canEasyConnect() {
        if (getWifiConfiguration() == null) {
            return false;
        }

        if (!mWifiManager.isEasyConnectSupported()) {
            return false;
        }

        // DPP 1.0 only supports WPA2 and WPA3.
        switch (mTargetSecurity) {
            case SECURITY_PSK:
            case SECURITY_SAE:
                return true;
            default:
                return false;
        }
    }

    @Override
    @MeteredChoice
    public int getMeteredChoice() {
        if (getWifiConfiguration() != null) {
            final int meteredOverride = getWifiConfiguration().meteredOverride;
            if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
                return METERED_CHOICE_METERED;
            } else if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
                return METERED_CHOICE_UNMETERED;
            }
        }
        return METERED_CHOICE_AUTO;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return getWifiConfiguration() != null;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        if (!canSetMeteredChoice()) {
            return;
        }

        if (meteredChoice == METERED_CHOICE_AUTO) {
            mTargetWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        } else if (meteredChoice == METERED_CHOICE_METERED) {
            mTargetWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        } else if (meteredChoice == METERED_CHOICE_UNMETERED) {
            mTargetWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        }
        mWifiManager.save(mTargetWifiConfig, null /* listener */);
    }

    @Override
    public boolean canSetPrivacy() {
        return isSaved();
    }

    @Override
    @Privacy
    public int getPrivacy() {
        if (mTargetWifiConfig != null
                && mTargetWifiConfig.macRandomizationSetting
                == WifiConfiguration.RANDOMIZATION_NONE) {
            return PRIVACY_DEVICE_MAC;
        } else {
            return PRIVACY_RANDOMIZED_MAC;
        }
    }

    @Override
    public void setPrivacy(int privacy) {
        if (!canSetPrivacy()) {
            return;
        }

        mTargetWifiConfig.macRandomizationSetting = privacy == PRIVACY_RANDOMIZED_MAC
                ? WifiConfiguration.RANDOMIZATION_AUTO : WifiConfiguration.RANDOMIZATION_NONE;
        mWifiManager.save(mTargetWifiConfig, null /* listener */);
    }

    @Override
    public boolean isAutoJoinEnabled() {
        if (mTargetWifiConfig == null) {
            return false;
        }

        return mTargetWifiConfig.allowAutojoin;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return isSaved() || isSuggestion();
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
        if (!canSetAutoJoinEnabled()) {
            return;
        }

        mWifiManager.allowAutojoin(mTargetWifiConfig.networkId, enabled);
    }

    @Override
    public String getSecurityString(boolean concise) {
        switch(mTargetSecurity) {
            case SECURITY_EAP:
                switch (mEapType) {
                    case EAP_WPA:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_eap_wpa) :
                                mContext.getString(R.string.wifitrackerlib_wifi_security_eap_wpa);
                    case EAP_WPA2_WPA3:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_eap_wpa2_wpa3) :
                                mContext.getString(
                                R.string.wifitrackerlib_wifi_security_eap_wpa2_wpa3);
                    case EAP_UNKNOWN:
                    default:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_eap) :
                                mContext.getString(R.string.wifitrackerlib_wifi_security_eap);
                }
            case SECURITY_EAP_SUITE_B:
                return concise ? mContext.getString(
                        R.string.wifitrackerlib_wifi_security_short_eap_suiteb) :
                        mContext.getString(R.string.wifitrackerlib_wifi_security_eap_suiteb);
            case SECURITY_PSK:
                switch (mPskType) {
                    case PSK_WPA:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_wpa) :
                                mContext.getString(R.string.wifitrackerlib_wifi_security_wpa);
                    case PSK_WPA2:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_wpa2_wpa3) :
                                mContext.getString(R.string.wifitrackerlib_wifi_security_wpa2_wpa3);
                    case PSK_WPA_WPA2:
                    case PSK_UNKNOWN:
                    default:
                        return concise ? mContext.getString(
                                R.string.wifitrackerlib_wifi_security_short_wpa_wpa2_wpa3) :
                                mContext.getString(
                                R.string.wifitrackerlib_wifi_security_wpa_wpa2_wpa3);
                }
            case SECURITY_WEP:
                return mContext.getString(R.string.wifitrackerlib_wifi_security_wep);
            case SECURITY_SAE:
                return concise ? mContext.getString(
                        R.string.wifitrackerlib_wifi_security_short_sae) :
                        mContext.getString(R.string.wifitrackerlib_wifi_security_sae);
            case SECURITY_OWE:
                return concise ? mContext.getString(
                        R.string.wifitrackerlib_wifi_security_short_owe) :
                        mContext.getString(R.string.wifitrackerlib_wifi_security_owe);
            case SECURITY_NONE:
            default:
                return concise ? "" : mContext.getString(
                        R.string.wifitrackerlib_wifi_security_none);
        }
    }

    @Override
    public boolean shouldEditBeforeConnect() {
        WifiConfiguration wifiConfig = getWifiConfiguration();
        if (wifiConfig == null) {
            return false;
        }

        // The network is disabled because of one of the authentication problems.
        NetworkSelectionStatus networkSelectionStatus = wifiConfig.getNetworkSelectionStatus();
        if (networkSelectionStatus.getNetworkSelectionStatus() != NETWORK_SELECTION_ENABLED) {
            if (networkSelectionStatus.getDisableReasonCounter(DISABLED_AUTHENTICATION_FAILURE) > 0
                    || networkSelectionStatus.getDisableReasonCounter(
                    DISABLED_BY_WRONG_PASSWORD) > 0
                    || networkSelectionStatus.getDisableReasonCounter(
                    DISABLED_AUTHENTICATION_NO_CREDENTIALS) > 0) {
                return true;
            }
        }

        return false;
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults == null) scanResults = new ArrayList<>();

        final String ssid = mKey.getScanResultKey().getSsid();
        for (ScanResult scan : scanResults) {
            if (!TextUtils.equals(scan.SSID, ssid)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID! Expected: "
                                + ssid + ", Actual: " + scan.SSID + ", ScanResult: " + scan);
            }
        }
        // Populate the cached scan result map
        mMatchingScanResults.clear();
        for (ScanResult scan : scanResults) {
            for (@Security int security : getSecurityTypesFromScanResult(scan)) {
                if (!isSecurityTypeSupported(security)) {
                    continue;
                }
                if (!mMatchingScanResults.containsKey(security)) {
                    mMatchingScanResults.put(security, new ArrayList<>());
                }
                mMatchingScanResults.get(security).add(scan);
            }
        }

        updateTargetSecurityTypes();
        updateTargetScanResultInfo();
        notifyOnUpdated();
    }

    private void updateTargetScanResultInfo() {
        // Update the level using the scans matching the target security type
        final ScanResult bestScanResult = getBestScanResultByLevel(mTargetScanResults);
        if (bestScanResult != null) {
            updateEapType(bestScanResult);
            updatePskType(bestScanResult);
        }

        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            mLevel = bestScanResult != null
                    ? mWifiManager.calculateSignalLevel(bestScanResult.level)
                    : WIFI_LEVEL_UNREACHABLE;
            synchronized (mLock) {
                // Average speed is used to prevent speed label flickering from multiple APs.
                mSpeed = getAverageSpeedFromScanResults(mScoreCache, mTargetScanResults);
            }
        }
    }

    @WorkerThread
    @Override
    void updateNetworkCapabilities(@Nullable NetworkCapabilities capabilities) {
        super.updateNetworkCapabilities(capabilities);

        // Auto-open an available captive portal if the user manually connected to this network.
        if (canSignIn() && mShouldAutoOpenCaptivePortal) {
            mShouldAutoOpenCaptivePortal = false;
            signIn(null /* callback */);
        }
    }

    @WorkerThread
    void onScoreCacheUpdated() {
        if (mWifiInfo != null) {
            mSpeed = getSpeedFromWifiInfo(mScoreCache, mWifiInfo);
        } else {
            synchronized (mLock) {
                // Average speed is used to prevent speed label flickering from multiple APs.
                mSpeed = getAverageSpeedFromScanResults(mScoreCache, mTargetScanResults);
            }
        }
        notifyOnUpdated();
    }

    private void updateEapType(ScanResult result) {
        if (result.capabilities.contains("RSN-EAP")) {
            // WPA2-Enterprise and WPA3-Enterprise (non 192-bit) advertise RSN-EAP-CCMP
            mEapType = EAP_WPA2_WPA3;
        } else if (result.capabilities.contains("WPA-EAP")) {
            // WPA-Enterprise advertises WPA-EAP-TKIP
            mEapType = EAP_WPA;
        } else {
            mEapType = EAP_UNKNOWN;
        }
    }

    private void updatePskType(ScanResult result) {
        if (mTargetSecurity != SECURITY_PSK) {
            mPskType = PSK_UNKNOWN;
            return;
        }

        final boolean wpa = result.capabilities.contains("WPA-PSK");
        final boolean wpa2 = result.capabilities.contains("RSN-PSK");
        if (wpa2 && wpa) {
            mPskType = PSK_WPA_WPA2;
        } else if (wpa2) {
            mPskType = PSK_WPA2;
        } else if (wpa) {
            mPskType = PSK_WPA;
        } else {
            mPskType = PSK_UNKNOWN;
        }
    }

    @WorkerThread
    void updateConfig(@Nullable List<WifiConfiguration> wifiConfigs)
            throws IllegalArgumentException {
        if (wifiConfigs == null) {
            wifiConfigs = Collections.emptyList();
        }

        final ScanResultKey scanResultKey = mKey.getScanResultKey();
        final String ssid = scanResultKey.getSsid();
        final Set<Integer> securityTypes = scanResultKey.getSecurityTypes();
        mMatchingWifiConfigs.clear();
        for (WifiConfiguration config : wifiConfigs) {
            if (!TextUtils.equals(ssid, sanitizeSsid(config.SSID))) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID!"
                                + " Expected: " + ssid
                                + ", Actual: " + sanitizeSsid(config.SSID)
                                + ", Config: " + config);
            }
            @Security int securityType = getSecurityTypeFromWifiConfiguration(config);
            if (!securityTypes.contains(securityType)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong security!"
                                + " Expected one of: " + securityTypes
                                + ", Actual: " + securityType
                                + ", Config: " + config);
            }
            mMatchingWifiConfigs.put(securityType, config);
        }
        updateTargetSecurityTypes();
        updateTargetScanResultInfo();
        notifyOnUpdated();
    }

    private boolean isSecurityTypeSupported(@Security int security) {
        switch (security) {
            case SECURITY_SAE:
                return mIsWpa3SaeSupported;
            case SECURITY_EAP_SUITE_B:
                return mIsWpa3SuiteBSupported;
            case SECURITY_OWE:
                return mIsEnhancedOpenSupported;
            default:
                return true;
        }
    }

    private void updateTargetSecurityTypes() {
        SortedSet<Integer> targetSecurityTypes = new TreeSet<>();
        if (mWifiInfo != null) {
            targetSecurityTypes.add(getSecurityTypeFromWifiInfo(mWifiInfo));
        }
        Set<Integer> keySecurityTypes = mKey.getScanResultKey().getSecurityTypes();
        // First try to find security types that matches both scans and configs
        if (targetSecurityTypes.isEmpty()
                && !mMatchingScanResults.isEmpty() && !mMatchingWifiConfigs.isEmpty()) {
            for (@Security int security : keySecurityTypes) {
                if (mMatchingScanResults.containsKey(security)
                        && mMatchingWifiConfigs.containsKey(security)
                        && isSecurityTypeSupported(security)) {
                    targetSecurityTypes.add(security);
                }
            }
        }
        // If no scan + config match, prioritize the security types of available scans, then
        // available configs, and finally just use the security types from the ScanResultKey
        if (targetSecurityTypes.isEmpty() && !mMatchingScanResults.isEmpty()) {
            for (@Security int security : keySecurityTypes) {
                if (mMatchingScanResults.containsKey(security)
                        && isSecurityTypeSupported(security)) {
                    targetSecurityTypes.add(security);
                }
            }
        }
        if (targetSecurityTypes.isEmpty() && !mMatchingWifiConfigs.isEmpty()) {
            for (@Security int security : keySecurityTypes) {
                if (mMatchingWifiConfigs.containsKey(security)
                        && isSecurityTypeSupported(security)) {
                    targetSecurityTypes.add(security);
                }
            }
        }
        if (targetSecurityTypes.isEmpty()) {
            targetSecurityTypes.addAll(keySecurityTypes);
        }
        // TODO: Refactor to allow multiple target security types instead of only picking one.
        if (targetSecurityTypes.contains(SECURITY_OWE)) {
            // Special case for preferring OWE security over open networks
            mTargetSecurity = SECURITY_OWE;
        } else {
            // Pick the lowest security type for wider compatibility
            mTargetSecurity = targetSecurityTypes.first();
        }

        mTargetWifiConfig = mMatchingWifiConfigs.get(mTargetSecurity);
        synchronized (mLock) {
            mTargetScanResults.clear();
            if (mMatchingScanResults.containsKey(mTargetSecurity)) {
                mTargetScanResults.addAll(mMatchingScanResults.get(mTargetSecurity));
            }
        }
    }

    /**
     * Sets whether the suggested config for this entry is shareable to the user or not.
     */
    @WorkerThread
    void setUserShareable(boolean isUserShareable) {
        mIsUserShareable = isUserShareable;
    }

    /**
     * Returns whether the suggested config for this entry is shareable to the user or not.
     */
    @WorkerThread
    boolean isUserShareable() {
        return mIsUserShareable;
    }

    @WorkerThread
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return false;
        }
        for (WifiConfiguration config : mMatchingWifiConfigs.values()) {
            if (config.networkId == wifiInfo.getNetworkId()) {
                return true;
            }
        }
        return false;
    }

    private void updateRecommendationServiceLabel() {
        final NetworkScorerAppData scorer = ((NetworkScoreManager) mContext
                .getSystemService(Context.NETWORK_SCORE_SERVICE)).getActiveScorer();
        if (scorer != null) {
            mRecommendationServiceLabel = scorer.getRecommendationServiceLabel();
        }
    }

    @NonNull
    static StandardWifiEntryKey ssidAndSecurityToStandardWifiEntryKey(@NonNull String ssid,
            @Security int security) {
        return new StandardWifiEntryKey(
                new ScanResultKey(ssid, Collections.singletonList(security)));
    }

    @Override
    protected String getScanResultDescription() {
        synchronized (mLock) {
            if (mTargetScanResults.size() == 0) {
                return "";
            }
        }

        final StringBuilder description = new StringBuilder();
        description.append("[");
        description.append(getScanResultDescription(MIN_FREQ_24GHZ, MAX_FREQ_24GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_5GHZ, MAX_FREQ_5GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_6GHZ, MAX_FREQ_6GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_60GHZ, MAX_FREQ_60GHZ));
        description.append("]");
        return description.toString();
    }

    private String getScanResultDescription(int minFrequency, int maxFrequency) {
        final List<ScanResult> scanResults;
        synchronized (mLock) {
            scanResults = mTargetScanResults.stream()
                    .filter(scanResult -> scanResult.frequency >= minFrequency
                            && scanResult.frequency <= maxFrequency)
                    .sorted(Comparator.comparingInt(scanResult -> -1 * scanResult.level))
                    .collect(Collectors.toList());
        }

        final int scanResultCount = scanResults.size();
        if (scanResultCount == 0) {
            return "";
        }

        final StringBuilder description = new StringBuilder();
        description.append("(").append(scanResultCount).append(")");
        if (scanResultCount > MAX_VERBOSE_LOG_DISPLAY_SCANRESULT_COUNT) {
            final int maxLavel = scanResults.stream()
                    .mapToInt(scanResult -> scanResult.level).max().getAsInt();
            description.append("max=").append(maxLavel).append(",");
        }
        final long nowMs = SystemClock.elapsedRealtime();
        scanResults.forEach(scanResult ->
                description.append(getScanResultDescription(scanResult, nowMs)));
        return description.toString();
    }

    private String getScanResultDescription(ScanResult scanResult, long nowMs) {
        final StringBuilder description = new StringBuilder();
        description.append(" \n{");
        description.append(scanResult.BSSID);
        if (mWifiInfo != null && scanResult.BSSID.equals(mWifiInfo.getBSSID())) {
            description.append("*");
        }
        description.append("=").append(scanResult.frequency);
        description.append(",").append(scanResult.level);
        final int ageSeconds = (int) (nowMs - scanResult.timestamp / 1000) / 1000;
        description.append(",").append(ageSeconds).append("s");
        description.append("}");
        return description.toString();
    }

    @Override
    String getNetworkSelectionDescription() {
        return Utils.getNetworkSelectionDescription(getWifiConfiguration());
    }

    /**
     * Class that identifies a unique StandardWifiEntry by the following identifiers
     *     1) ScanResult key (SSID + grouped security types)
     *     2) Suggestion profile key
     *     3) Is network request or not
     */
    static class StandardWifiEntryKey {
        private static final String KEY_SCAN_RESULT_KEY = "SCAN_RESULT_KEY";
        private static final String KEY_SUGGESTION_PROFILE_KEY = "SUGGESTION_PROFILE_KEY";
        private static final String KEY_IS_NETWORK_REQUEST = "IS_NETWORK_REQUEST";

        @NonNull private ScanResultKey mScanResultKey;
        @Nullable private String mSuggestionProfileKey;
        private boolean mIsNetworkRequest;

        /**
         * Creates a StandardWifiEntryKey matching a ScanResultKey
         */
        StandardWifiEntryKey(@NonNull ScanResultKey scanResultKey) {
            mScanResultKey = scanResultKey;
        }

        /**
         * Creates a StandardWifiEntryKey matching a WifiConfiguration
         */
        StandardWifiEntryKey(@NonNull WifiConfiguration config) {
            mScanResultKey = new ScanResultKey(config);
            if (config.fromWifiNetworkSuggestion) {
                mSuggestionProfileKey = new StringJoiner(",")
                        .add(config.creatorName)
                        .add(String.valueOf(config.carrierId))
                        .add(String.valueOf(config.subscriptionId))
                        .toString();
            } else if (config.fromWifiNetworkSpecifier) {
                mIsNetworkRequest = true;
            }
        }

        /**
         * Creates a StandardWifiEntryKey from its String representation.
         */
        StandardWifiEntryKey(@NonNull String string) {
            mScanResultKey = new ScanResultKey();
            if (!string.startsWith(KEY_PREFIX)) {
                Log.e(TAG, "String key does not start with key prefix!");
                return;
            }
            try {
                final JSONObject keyJson = new JSONObject(string.substring(KEY_PREFIX.length()));
                if (keyJson.has(KEY_SCAN_RESULT_KEY)) {
                    mScanResultKey = new ScanResultKey(keyJson.getString(KEY_SCAN_RESULT_KEY));
                }
                if (keyJson.has(KEY_SUGGESTION_PROFILE_KEY)) {
                    mSuggestionProfileKey = keyJson.getString(KEY_SUGGESTION_PROFILE_KEY);
                }
                if (keyJson.has(KEY_IS_NETWORK_REQUEST)) {
                    mIsNetworkRequest = keyJson.getBoolean(KEY_IS_NETWORK_REQUEST);
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSONException while converting StandardWifiEntryKey to string: " + e);
            }
        }

        /**
         * Returns the JSON String representation of this StandardWifiEntryKey.
         */
        @Override
        public String toString() {
            final JSONObject keyJson = new JSONObject();
            try {
                if (mScanResultKey != null) {
                    keyJson.put(KEY_SCAN_RESULT_KEY, mScanResultKey.toString());
                }
                if (mSuggestionProfileKey != null) {
                    keyJson.put(KEY_SUGGESTION_PROFILE_KEY, mSuggestionProfileKey);
                }
                if (mIsNetworkRequest) {
                    keyJson.put(KEY_IS_NETWORK_REQUEST, mIsNetworkRequest);
                }
            } catch (JSONException e) {
                Log.wtf(TAG, "JSONException while converting StandardWifiEntryKey to string: " + e);
            }
            return KEY_PREFIX + keyJson.toString();
        }

        /**
         * Returns the ScanResultKey of this StandardWifiEntryKey to match against ScanResults
         */
        @NonNull ScanResultKey getScanResultKey() {
            return mScanResultKey;
        }

        @Nullable String getSuggestionProfileKey() {
            return mSuggestionProfileKey;
        }

        boolean isNetworkRequest() {
            return mIsNetworkRequest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StandardWifiEntryKey that = (StandardWifiEntryKey) o;
            return Objects.equals(mScanResultKey, that.mScanResultKey)
                    && TextUtils.equals(mSuggestionProfileKey, that.mSuggestionProfileKey)
                    && mIsNetworkRequest == that.mIsNetworkRequest;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mScanResultKey, mSuggestionProfileKey, mIsNetworkRequest);
        }
    }

    /**
     * Class for matching ScanResults to StandardWifiEntry by SSID and security type grouping.
     */
    static class ScanResultKey {
        private static final String KEY_SSID = "SSID";
        private static final String KEY_SECURITY_TYPES = "SECURITY_TYPES";

        @Nullable private String mSsid;
        @NonNull private Set<Integer> mSecurityTypes = new HashSet<>();

        ScanResultKey() {
        }

        ScanResultKey(@Nullable String ssid, List<Integer> securityTypes) {
            mSsid = ssid;
            for (@Security int security : securityTypes) {
                mSecurityTypes.add(security);
                // Add any security types that merge to the same WifiEntry
                switch (security) {
                    // Group Open and OWE networks together
                    case SECURITY_NONE:
                        mSecurityTypes.add(SECURITY_OWE);
                        break;
                    case SECURITY_OWE:
                        mSecurityTypes.add(SECURITY_NONE);
                        break;
                    // Group PSK and SAE networks together
                    case SECURITY_PSK:
                        mSecurityTypes.add(SECURITY_SAE);
                        break;
                    case SECURITY_SAE:
                        mSecurityTypes.add(SECURITY_PSK);
                        break;
                }
            }
        }

        /**
         * Creates a ScanResultKey from a ScanResult's SSID and security type grouping.
         * @param scanResult
         */
        ScanResultKey(@NonNull ScanResult scanResult) {
            this(scanResult.SSID, getSecurityTypesFromScanResult(scanResult));
        }

        /**
         * Creates a ScanResultKey from a WifiConfiguration's SSID and security type grouping.
         */
        ScanResultKey(@NonNull WifiConfiguration wifiConfiguration) {
            this(sanitizeSsid(wifiConfiguration.SSID), Collections.singletonList(
                    getSecurityTypeFromWifiConfiguration(wifiConfiguration)));
        }

        /**
         * Creates a ScanResultKey from its String representation.
         */
        ScanResultKey(@NonNull String string) {
            try {
                final JSONObject keyJson = new JSONObject(string);
                mSsid = keyJson.getString(KEY_SSID);
                final JSONArray securityTypesJson =
                        keyJson.getJSONArray(KEY_SECURITY_TYPES);
                for (int i = 0; i < securityTypesJson.length(); i++) {
                    mSecurityTypes.add(securityTypesJson.getInt(i));
                }
            } catch (JSONException e) {
                Log.wtf(TAG, "JSONException while constructing ScanResultKey from string: " + e);
            }
        }

        /**
         * Returns the JSON String representation of this ScanResultEntry.
         */
        @Override
        public String toString() {
            final JSONObject keyJson = new JSONObject();
            try {
                if (mSsid != null) {
                    keyJson.put(KEY_SSID, mSsid);
                }
                if (!mSecurityTypes.isEmpty()) {
                    final JSONArray securityTypesJson = new JSONArray();
                    for (@Security int security : mSecurityTypes) {
                        securityTypesJson.put(security);
                    }
                    keyJson.put(KEY_SECURITY_TYPES, securityTypesJson);
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSONException while converting ScanResultKey to string: " + e);
            }
            return keyJson.toString();
        }

        @Nullable String getSsid() {
            return mSsid;
        }

        @NonNull Set<Integer> getSecurityTypes() {
            return mSecurityTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ScanResultKey that = (ScanResultKey) o;
            return TextUtils.equals(mSsid, that.mSsid)
                    && mSecurityTypes.equals(that.mSecurityTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSsid, mSecurityTypes);
        }
    }
}
