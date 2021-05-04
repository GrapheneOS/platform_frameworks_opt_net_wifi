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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED;

import static com.android.wifitrackerlib.WifiEntry.SECURITY_EAP;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_EAP_SUITE_B;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_EAP_WPA3_ENTERPRISE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_NONE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_OWE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_PSK;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_SAE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_WEP;
import static com.android.wifitrackerlib.WifiEntry.SPEED_FAST;
import static com.android.wifitrackerlib.WifiEntry.SPEED_MODERATE;
import static com.android.wifitrackerlib.WifiEntry.SPEED_NONE;
import static com.android.wifitrackerlib.WifiEntry.SPEED_SLOW;
import static com.android.wifitrackerlib.WifiEntry.SPEED_VERY_FAST;
import static com.android.wifitrackerlib.WifiEntry.Speed;

import static java.util.Comparator.comparingInt;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.Annotation;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.HelpUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * Utility methods for WifiTrackerLib.
 */
public class Utils {
    /** Copy of the @hide Settings.Global.USE_OPEN_WIFI_PACKAGE constant. */
    static final String SETTINGS_GLOBAL_USE_OPEN_WIFI_PACKAGE = "use_open_wifi_package";

    private static NetworkScoreManager sNetworkScoreManager;

    private static String getActiveScorerPackage(@NonNull Context context) {
        if (sNetworkScoreManager == null) {
            sNetworkScoreManager = context.getSystemService(NetworkScoreManager.class);
        }
        return sNetworkScoreManager.getActiveScorerPackage();
    }

    // Returns the ScanResult with the best RSSI from a list of ScanResults.
    @Nullable
    public static ScanResult getBestScanResultByLevel(@NonNull List<ScanResult> scanResults) {
        if (scanResults.isEmpty()) return null;

        return Collections.max(scanResults, comparingInt(scanResult -> scanResult.level));
    }

    // Returns a list of SECURITY types supported by a ScanResult.
    static List<Integer> getSecurityTypesFromScanResult(@NonNull ScanResult scan) {
        final List<Integer> securityTypes = new ArrayList<>();
        if (scan.capabilities == null) {
            securityTypes.add(SECURITY_NONE);
        } else if (scan.capabilities.contains("PSK") && scan.capabilities.contains("SAE")) {
            securityTypes.add(SECURITY_PSK);
            securityTypes.add(SECURITY_SAE);
        } else if (scan.capabilities.contains("OWE_TRANSITION")) {
            securityTypes.add(SECURITY_NONE);
            securityTypes.add(SECURITY_OWE);
        } else if (scan.capabilities.contains("OWE")) {
            securityTypes.add(SECURITY_OWE);
        } else if (scan.capabilities.contains("WEP")) {
            securityTypes.add(SECURITY_WEP);
        } else if (scan.capabilities.contains("SAE")) {
            securityTypes.add(SECURITY_SAE);
        } else if (scan.capabilities.contains("PSK")) {
            securityTypes.add(SECURITY_PSK);
        } else if (scan.capabilities.contains("EAP_SUITE_B_192")) {
            securityTypes.add(SECURITY_EAP_SUITE_B);
        } else if (scan.capabilities.contains("EAP")) {
            securityTypes.add(SECURITY_EAP);
        } else {
            securityTypes.add(SECURITY_NONE);
        }
        return securityTypes;
    }

    // Returns the SECURITY type supported by a WifiConfiguration
    @WifiEntry.Security
    static int getSecurityTypeFromWifiConfiguration(@NonNull WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            return SECURITY_SAE;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return SECURITY_PSK;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
            return SECURITY_EAP_SUITE_B;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            return SECURITY_EAP;
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
            return SECURITY_OWE;
        }
        return (config.wepKeys[0] != null) ? SECURITY_WEP : SECURITY_NONE;
    }

    // Returns the SECURITY type of the current connection in WifiInfo
    @WifiEntry.Security
    static int getSecurityTypeFromWifiInfo(@NonNull WifiInfo wifiInfo) {
        switch (wifiInfo.getCurrentSecurityType()) {
            case WifiInfo.SECURITY_TYPE_WEP:
                return SECURITY_WEP;
            case WifiInfo.SECURITY_TYPE_PSK:
                return SECURITY_PSK;
            case WifiInfo.SECURITY_TYPE_EAP:
                return SECURITY_EAP;
            case WifiInfo.SECURITY_TYPE_SAE:
                return SECURITY_SAE;
            case WifiInfo.SECURITY_TYPE_OWE:
                return SECURITY_OWE;
            case WifiInfo.SECURITY_TYPE_WAPI_PSK:
            case WifiInfo.SECURITY_TYPE_WAPI_CERT:
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
                return SECURITY_EAP_WPA3_ENTERPRISE;
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT:
                return SECURITY_EAP_SUITE_B;
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2:
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R3:
                // TODO: Create new security types for Passpoint
                return SECURITY_EAP;
            default:
                return SECURITY_NONE;
        }
    }

    @Speed
    public static int getAverageSpeedFromScanResults(@NonNull WifiNetworkScoreCache scoreCache,
            @NonNull List<ScanResult> scanResults) {
        int count = 0;
        int totalSpeed = 0;
        for (ScanResult scanResult : scanResults) {
            ScoredNetwork scoredNetwork = scoreCache.getScoredNetwork(scanResult);
            if (scoredNetwork == null) {
                continue;
            }
            @Speed int speed = scoredNetwork.calculateBadge(scanResult.level);
            if (speed != SPEED_NONE) {
                count++;
                totalSpeed += speed;
            }
        }
        if (count == 0) {
            return SPEED_NONE;
        } else {
            return roundToClosestSpeedEnum(totalSpeed / count);
        }
    }

    @Speed
    public static int getSpeedFromWifiInfo(@NonNull WifiNetworkScoreCache scoreCache,
            @NonNull WifiInfo wifiInfo) {
        final WifiKey wifiKey;
        try {
            wifiKey = new WifiKey(wifiInfo.getSSID(), wifiInfo.getBSSID());
        } catch (IllegalArgumentException e) {
            return SPEED_NONE;
        }
        ScoredNetwork scoredNetwork = scoreCache.getScoredNetwork(
                new NetworkKey(wifiKey));
        if (scoredNetwork == null) {
            return SPEED_NONE;
        }
        return roundToClosestSpeedEnum(scoredNetwork.calculateBadge(wifiInfo.getRssi()));
    }

    @Speed
    private static int roundToClosestSpeedEnum(int speed) {
        if (speed == SPEED_NONE) {
            return SPEED_NONE;
        } else if (speed < (SPEED_SLOW + SPEED_MODERATE) / 2) {
            return SPEED_SLOW;
        } else if (speed < (SPEED_MODERATE + SPEED_FAST) / 2) {
            return SPEED_MODERATE;
        } else if (speed < (SPEED_FAST + SPEED_VERY_FAST) / 2) {
            return SPEED_FAST;
        } else {
            return SPEED_VERY_FAST;
        }
    }

    /**
     * Get the app label for a suggestion/specifier package name, or an empty String if none exist
     */
    static String getAppLabel(Context context, String packageName) {
        try {
            String openWifiPackageName = Settings.Global.getString(context.getContentResolver(),
                    SETTINGS_GLOBAL_USE_OPEN_WIFI_PACKAGE);
            if (!TextUtils.isEmpty(openWifiPackageName) && TextUtils.equals(packageName,
                    getActiveScorerPackage(context))) {
                packageName = openWifiPackageName;
            }

            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                    packageName,
                    0 /* flags */);
            return appInfo.loadLabel(context.getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static String getDisconnectedStateDescription(Context context, WifiEntry wifiEntry) {
        if (context == null || wifiEntry == null) {
            return "";
        }
        WifiConfiguration wifiConfiguration = wifiEntry.getWifiConfiguration();
        if (wifiConfiguration == null) {
            return null;
        }

        if (wifiConfiguration.hasNoInternetAccess()) {
            int messageID =
                    wifiConfiguration.getNetworkSelectionStatus().getNetworkSelectionStatus()
                            == NETWORK_SELECTION_PERMANENTLY_DISABLED
                    ? R.string.wifitrackerlib_wifi_no_internet_no_reconnect
                    : R.string.wifitrackerlib_wifi_no_internet;
            return context.getString(messageID);
        } else if (wifiConfiguration.getNetworkSelectionStatus().getNetworkSelectionStatus()
                != NETWORK_SELECTION_ENABLED) {
            WifiConfiguration.NetworkSelectionStatus networkStatus =
                    wifiConfiguration.getNetworkSelectionStatus();
            switch (networkStatus.getNetworkSelectionDisableReason()) {
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE:
                case WifiConfiguration.NetworkSelectionStatus
                        .DISABLED_AUTHENTICATION_NO_SUBSCRIPTION:
                    return context.getString(
                            R.string.wifitrackerlib_wifi_disabled_password_failure);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD:
                    return context.getString(R.string.wifitrackerlib_wifi_check_password_try_again);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE:
                    return context.getString(R.string.wifitrackerlib_wifi_disabled_network_failure);
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION:
                    return context.getString(R.string.wifitrackerlib_wifi_disabled_generic);
                default:
                    break;
            }
        } else if (wifiEntry.getLevel() == WifiEntry.WIFI_LEVEL_UNREACHABLE) {
            // Do nothing because users know it by signal icon.
        } else { // In range, not disabled.
            switch (wifiConfiguration.getRecentFailureReason()) {
                case WifiConfiguration.RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA:
                case WifiConfiguration.RECENT_FAILURE_REFUSED_TEMPORARILY:
                case WifiConfiguration.RECENT_FAILURE_DISCONNECTION_AP_BUSY:
                    return context.getString(R.string
                            .wifitrackerlib_wifi_ap_unable_to_handle_new_sta);
                case WifiConfiguration.RECENT_FAILURE_POOR_CHANNEL_CONDITIONS:
                    return context.getString(R.string.wifitrackerlib_wifi_poor_channel_conditions);
                case WifiConfiguration.RECENT_FAILURE_MBO_ASSOC_DISALLOWED_UNSPECIFIED:
                case WifiConfiguration.RECENT_FAILURE_MBO_ASSOC_DISALLOWED_AIR_INTERFACE_OVERLOADED:
                case WifiConfiguration.RECENT_FAILURE_MBO_ASSOC_DISALLOWED_AUTH_SERVER_OVERLOADED:
                    return context.getString(R.string
                            .wifitrackerlib_wifi_mbo_assoc_disallowed_cannot_connect);
                case WifiConfiguration.RECENT_FAILURE_MBO_ASSOC_DISALLOWED_MAX_NUM_STA_ASSOCIATED:
                    return context.getString(R.string
                            .wifitrackerlib_wifi_mbo_assoc_disallowed_max_num_sta_associated);
                case WifiConfiguration.RECENT_FAILURE_MBO_ASSOC_DISALLOWED_INSUFFICIENT_RSSI:
                case WifiConfiguration.RECENT_FAILURE_OCE_RSSI_BASED_ASSOCIATION_REJECTION:
                    return context.getString(R.string
                            .wifitrackerlib_wifi_mbo_oce_assoc_disallowed_insufficient_rssi);
                case WifiConfiguration.RECENT_FAILURE_NETWORK_NOT_FOUND:
                    return context.getString(R.string.wifitrackerlib_wifi_network_not_found);
                default:
                    // do nothing
            }
        }
        return "";
    }

    static String getAutoConnectDescription(@NonNull Context context,
            @NonNull WifiEntry wifiEntry) {
        if (context == null || wifiEntry == null || !wifiEntry.canSetAutoJoinEnabled()) {
            return "";
        }

        return wifiEntry.isAutoJoinEnabled()
                ? "" : context.getString(R.string.wifitrackerlib_auto_connect_disable);
    }

    static String getMeteredDescription(@NonNull Context context, @Nullable WifiEntry wifiEntry) {
        if (context == null || wifiEntry == null) {
            return "";
        }

        if (!wifiEntry.canSetMeteredChoice()
                && wifiEntry.getMeteredChoice() != WifiEntry.METERED_CHOICE_METERED) {
            return "";
        }

        if (wifiEntry.getMeteredChoice() == WifiEntry.METERED_CHOICE_METERED) {
            return context.getString(R.string.wifitrackerlib_wifi_metered_label);
        } else if (wifiEntry.getMeteredChoice() == WifiEntry.METERED_CHOICE_UNMETERED) {
            return context.getString(R.string.wifitrackerlib_wifi_unmetered_label);
        } else { // METERED_CHOICE_AUTO
            return wifiEntry.isMetered() ? context.getString(
                    R.string.wifitrackerlib_wifi_metered_label) : "";
        }
    }

    static String getSpeedDescription(@NonNull Context context, @NonNull WifiEntry wifiEntry) {
        if (context == null || wifiEntry == null) {
            return "";
        }

        @Speed int speed = wifiEntry.getSpeed();
        switch (speed) {
            case SPEED_VERY_FAST:
                return context.getString(R.string.wifitrackerlib_speed_label_very_fast);
            case SPEED_FAST:
                return context.getString(R.string.wifitrackerlib_speed_label_fast);
            case SPEED_MODERATE:
                return context.getString(R.string.wifitrackerlib_speed_label_okay);
            case SPEED_SLOW:
                return context.getString(R.string.wifitrackerlib_speed_label_slow);
            case SPEED_NONE:
            default:
                return "";
        }
    }

    static String getVerboseLoggingDescription(@NonNull WifiEntry wifiEntry) {
        if (!BaseWifiTracker.isVerboseLoggingEnabled() || wifiEntry == null) {
            return "";
        }

        final StringJoiner sj = new StringJoiner(" ");

        final String wifiInfoDescription = wifiEntry.getWifiInfoDescription();
        if (!TextUtils.isEmpty(wifiInfoDescription)) {
            sj.add(wifiInfoDescription);
        }

        final String networkCapabilityDescription = wifiEntry.getNetworkCapabilityDescription();
        if (!TextUtils.isEmpty(networkCapabilityDescription)) {
            sj.add(networkCapabilityDescription);
        }

        final String scanResultsDescription = wifiEntry.getScanResultDescription();
        if (!TextUtils.isEmpty(scanResultsDescription)) {
            sj.add(scanResultsDescription);
        }

        final String networkSelectionDescription = wifiEntry.getNetworkSelectionDescription();
        if (!TextUtils.isEmpty(networkSelectionDescription)) {
            sj.add(networkSelectionDescription);
        }

        return sj.toString();
    }

    static String getNetworkSelectionDescription(WifiConfiguration wifiConfig) {
        if (wifiConfig == null) {
            return "";
        }

        StringBuilder description = new StringBuilder();
        NetworkSelectionStatus networkSelectionStatus = wifiConfig.getNetworkSelectionStatus();

        if (networkSelectionStatus.getNetworkSelectionStatus() != NETWORK_SELECTION_ENABLED) {
            description.append(" (" + networkSelectionStatus.getNetworkStatusString());
            if (networkSelectionStatus.getDisableTime() > 0) {
                long now = System.currentTimeMillis();
                long elapsedSeconds = (now - networkSelectionStatus.getDisableTime()) / 1000;
                description.append(" " + DateUtils.formatElapsedTime(elapsedSeconds));
            }
            description.append(")");
        }

        int maxNetworkSelectionDisableReason =
                NetworkSelectionStatus.getMaxNetworkSelectionDisableReason();
        for (int reason = 0; reason <= maxNetworkSelectionDisableReason; reason++) {
            int disableReasonCounter = networkSelectionStatus.getDisableReasonCounter(reason);
            if (disableReasonCounter == 0) {
                continue;
            }
            description.append(" ")
                    .append(NetworkSelectionStatus.getNetworkSelectionDisableReasonString(reason))
                    .append("=")
                    .append(disableReasonCounter);
        }
        return description.toString();
    }

    static String getCurrentNetworkCapabilitiesInformation(Context context,
            NetworkCapabilities networkCapabilities) {
        if (context == null || networkCapabilities == null) {
            return "";
        }

        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return context.getString(context.getResources()
                    .getIdentifier("network_available_sign_in", "string", "android"));
        }

        if (networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
            return context.getString(R.string.wifitrackerlib_wifi_limited_connection);
        }

        if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (networkCapabilities.isPrivateDnsBroken()) {
                return context.getString(R.string.wifitrackerlib_private_dns_broken);
            }
            return context.getString(
                R.string.wifitrackerlib_wifi_connected_cannot_provide_internet);
        }
        return "";
    }

    static String getNetworkDetailedState(Context context, NetworkInfo networkInfo) {
        if (context == null || networkInfo == null) {
            return "";
        }
        DetailedState detailState = networkInfo.getDetailedState();
        if (detailState == null) {
            return "";
        }

        String[] wifiStatusArray = context.getResources()
                .getStringArray(R.array.wifitrackerlib_wifi_status);
        int index = detailState.ordinal();
        return index >= wifiStatusArray.length ? "" : wifiStatusArray[index];
    }

    /**
     * Check if the SIM is present for target carrier Id.
     */
    static boolean isSimPresent(@NonNull Context context, int carrierId) {
        SubscriptionManager subscriptionManager =
                (SubscriptionManager) context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) return false;
        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null || subInfoList.isEmpty()) {
            return false;
        }
        return subInfoList.stream()
                .anyMatch(info -> info.getCarrierId() == carrierId);
    }

    /**
     * Get the SIM carrier name for target subscription Id.
     */
    static @Nullable String getCarrierNameForSubId(@NonNull Context context, int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return null;
        }
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) return null;
        TelephonyManager specifiedTm = telephonyManager.createForSubscriptionId(subId);
        if (specifiedTm == null) {
            return null;
        }
        CharSequence name = specifiedTm.getSimCarrierIdName();
        if (name == null) {
            return null;
        }
        return name.toString();
    }

    static boolean isSimCredential(@NonNull WifiConfiguration config) {
        return config.enterpriseConfig != null
                && config.enterpriseConfig.isAuthenticationSimBased();
    }

    /**
     * Get the best match subscription Id for target WifiConfiguration.
     */
    static int getSubIdForConfig(@NonNull Context context, @NonNull WifiConfiguration config) {
        if (config.carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        SubscriptionManager subscriptionManager =
                (SubscriptionManager) context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null || subInfoList.isEmpty()) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }

        int matchSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        for (SubscriptionInfo subInfo : subInfoList) {
            if (subInfo.getCarrierId() == config.carrierId) {
                matchSubId = subInfo.getSubscriptionId();
                if (matchSubId == dataSubId) {
                    // Priority of Data sub is higher than non data sub.
                    break;
                }
            }
        }
        return matchSubId;
    }

    /**
     * Check if target subscription Id requires IMSI privacy protection.
     */
    static boolean isImsiPrivacyProtectionProvided(@NonNull Context context, int subId) {
        CarrierConfigManager carrierConfigManager =
                (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null) {
            return false;
        }
        PersistableBundle bundle = carrierConfigManager.getConfigForSubId(subId);
        if (bundle == null) {
            return false;
        }
        return (bundle.getInt(CarrierConfigManager.IMSI_KEY_AVAILABILITY_INT)
                & TelephonyManager.KEY_TYPE_WLAN) != 0;
    }

    static CharSequence getImsiProtectionDescription(Context context,
            @Nullable WifiConfiguration wifiConfig) {
        if (context == null || wifiConfig == null || !isSimCredential(wifiConfig)) {
            return "";
        }
        int subId;
        if (wifiConfig.carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
            // Config without carrierId use default data subscription.
            subId = SubscriptionManager.getDefaultSubscriptionId();
        } else {
            subId = getSubIdForConfig(context, wifiConfig);
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                || isImsiPrivacyProtectionProvided(context, subId)) {
            return "";
        }

        // IMSI protection is not provided, return warning message.
        return linkifyAnnotation(context, context.getText(
                R.string.wifitrackerlib_imsi_protection_warning), "url",
                context.getString(R.string.wifitrackerlib_help_url_imsi_protection));
    }

    /** Find the annotation of specified id in rawText and linkify it with helpUriString. */
    static CharSequence linkifyAnnotation(Context context, CharSequence rawText, String id,
            String helpUriString) {
        // Return original string when helpUriString is empty.
        if (TextUtils.isEmpty(helpUriString)) {
            return rawText;
        }

        SpannableString spannableText = new SpannableString(rawText);
        Annotation[] annotations = spannableText.getSpans(0, spannableText.length(),
                Annotation.class);

        for (Annotation annotation : annotations) {
            if (TextUtils.equals(annotation.getValue(), id)) {
                SpannableStringBuilder builder = new SpannableStringBuilder(spannableText);
                ClickableSpan link = new ClickableSpan() {
                    @Override
                    public void onClick(View view) {
                        view.startActivityForResult(HelpUtils.getHelpIntent(context, helpUriString,
                                view.getClass().getName()), 0);
                    }
                };
                builder.setSpan(link, spannableText.getSpanStart(annotation),
                        spannableText.getSpanEnd(annotation), spannableText.getSpanFlags(link));
                return builder;
            }
        }
        return rawText;
    }
}
