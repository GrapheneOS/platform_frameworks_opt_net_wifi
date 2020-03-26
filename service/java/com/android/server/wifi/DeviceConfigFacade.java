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

package com.android.server.wifi;

import static com.android.server.wifi.util.InformationElementUtil.BssLoad.CHANNEL_UTILIZATION_SCALE;

import android.content.Context;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    private Context mContext;
    private final WifiMetrics mWifiMetrics;

    private static final String NAMESPACE = "wifi";

    // Default values of fields
    @VisibleForTesting
    protected static final int DEFAULT_ABNORMAL_CONNECTION_DURATION_MS =
            (int) TimeUnit.SECONDS.toMillis(30);
    // Default duration for evaluating Wifi condition to trigger a data stall
    // measured in milliseconds
    public static final int DEFAULT_DATA_STALL_DURATION_MS = 1500;
    // Default threshold of Tx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS = 2000;
    // Default threshold of Rx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS = 2000;
    // Default threshold of Tx packet error rate above which to trigger a data stall in percentage
    public static final int DEFAULT_DATA_STALL_TX_PER_THR = 90;
    // Default threshold of CCA level above which to trigger a data stall
    public static final int DEFAULT_DATA_STALL_CCA_LEVEL_THR = CHANNEL_UTILIZATION_SCALE;
    // Default low threshold of L2 sufficient throughput in Kbps
    public static final int DEFAULT_TPUT_SUFFICIENT_THR_LOW_KBPS = 1000;
    // Default high threshold of L2 sufficient throughput in Kbps
    public static final int DEFAULT_TPUT_SUFFICIENT_THR_HIGH_KBPS = 4000;
    // Numerator part of default threshold of L2 throughput over L3 throughput ratio
    public static final int DEFAULT_TPUT_SUFFICIENT_RATIO_THR_NUM = 2;
    // Denominator part of default threshold of L2 throughput over L3 throughput ratio
    public static final int DEFAULT_TPUT_SUFFICIENT_RATIO_THR_DEN = 1;
    // Default threshold of Tx packet per second
    public static final int DEFAULT_TX_PACKET_PER_SECOND_THR = 1;
    // Default threshold of Rx packet per second
    public static final int DEFAULT_RX_PACKET_PER_SECOND_THR = 1;
    // Default high threshold values for various connection/disconnection cases
    // All of them are in percent with respect to connection attempts
    static final int DEFAULT_CONNECTION_FAILURE_HIGH_THR_PERCENT = 30;
    static final int DEFAULT_ASSOC_REJECTION_HIGH_THR_PERCENT = 20;
    static final int DEFAULT_ASSOC_TIMEOUT_HIGH_THR_PERCENT = 20;
    static final int DEFAULT_AUTH_FAILURE_HIGH_THR_PERCENT = 20;
    static final int DEFAULT_SHORT_CONNECTION_NONLOCAL_HIGH_THR_PERCENT = 15;
    static final int DEFAULT_DISCONNECTION_NONLOCAL_HIGH_THR_PERCENT = 20;
    // Default health monitor abnormal count minimum for various cases
    static final int DEFAULT_CONNECTION_FAILURE_COUNT_MIN = 6;
    static final int DEFAULT_ASSOC_REJECTION_COUNT_MIN  = 3;
    static final int DEFAULT_ASSOC_TIMEOUT_COUNT_MIN  = 3;
    static final int DEFAULT_AUTH_FAILURE_COUNT_MIN  = 3;
    static final int DEFAULT_SHORT_CONNECTION_NONLOCAL_COUNT_MIN  = 3;
    static final int DEFAULT_DISCONNECTION_NONLOCAL_COUNT_MIN  = 3;
    // Numerator part of default ratio threshold values for all cases
    static final int DEFAULT_HEALTH_MONITOR_RATIO_THR_NUMERATOR = 4;
    // Denominator part of ratio threshold for all cases
    static final int HEALTH_MONITOR_RATIO_THR_DENOMINATOR = 2;
    // Minimum RSSI in dBm for connection stats collection
    // Connection or disconnection events with RSSI below this threshold are not
    // included in connection stats collection.
    static final int DEFAULT_HEALTH_MONITOR_MIN_RSSI_THR_DBM = -68;
    // Default minimum number of connection attempts to qualify daily detection
    static final int DEFAULT_HEALTH_MONITOR_MIN_NUM_CONNECTION_ATTEMPT = 10;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private boolean mIsAbnormalConnectionBugreportEnabled;
    private int mAbnormalConnectionDurationMs;
    private int mDataStallDurationMs;
    private int mDataStallTxTputThrKbps;
    private int mDataStallRxTputThrKbps;
    private int mDataStallTxPerThr;
    private int mDataStallCcaLevelThr;
    private int mTputSufficientLowThrKbps;
    private int mTputSufficientHighThrKbps;
    private int mTputSufficientRatioThrNum;
    private int mTputSufficientRatioThrDen;
    private int mTxPktPerSecondThr;
    private int mRxPktPerSecondThr;
    private int mConnectionFailureHighThrPercent;
    private int mConnectionFailureCountMin;
    private int mAssocRejectionHighThrPercent;
    private int mAssocRejectionCountMin;
    private int mAssocTimeoutHighThrPercent;
    private int mAssocTimeoutCountMin;
    private int mAuthFailureHighThrPercent;
    private int mAuthFailureCountMin;
    private int mShortConnectionNonlocalHighThrPercent;
    private int mShortConnectionNonlocalCountMin;
    private int mDisconnectionNonlocalHighThrPercent;
    private int mDisconnectionNonlocalCountMin;
    private int mHealthMonitorRatioThrNumerator;
    private int mHealthMonitorMinRssiThrDbm;
    private Set<String> mRandomizationFlakySsidHotlist;
    private Set<String> mAggressiveMacRandomizationSsidAllowlist;
    private Set<String> mAggressiveMacRandomizationSsidBlocklist;
    private boolean mIsAbnormalEapAuthFailureBugreportEnabled;
    private int mHealthMonitorMinNumConnectionAttempt;

    public DeviceConfigFacade(Context context, Handler handler, WifiMetrics wifiMetrics) {
        mContext = context;
        mWifiMetrics = wifiMetrics;

        updateDeviceConfigFlags();
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE,
                command -> handler.post(command),
                properties -> {
                    updateDeviceConfigFlags();
                });
    }

    private void updateDeviceConfigFlags() {
        mIsAbnormalConnectionBugreportEnabled = DeviceConfig.getBoolean(NAMESPACE,
                "abnormal_connection_bugreport_enabled", false);
        mAbnormalConnectionDurationMs = DeviceConfig.getInt(NAMESPACE,
                "abnormal_connection_duration_ms",
                DEFAULT_ABNORMAL_CONNECTION_DURATION_MS);

        mDataStallDurationMs = DeviceConfig.getInt(NAMESPACE,
                "data_stall_duration_ms", DEFAULT_DATA_STALL_DURATION_MS);
        mDataStallTxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_tput_thr_kbps", DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS);
        mDataStallRxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_rx_tput_thr_kbps", DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS);
        mDataStallTxPerThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_per_thr", DEFAULT_DATA_STALL_TX_PER_THR);
        mDataStallCcaLevelThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_cca_level_thr", DEFAULT_DATA_STALL_CCA_LEVEL_THR);
        mWifiMetrics.setDataStallDurationMs(mDataStallDurationMs);
        mWifiMetrics.setDataStallTxTputThrKbps(mDataStallTxTputThrKbps);
        mWifiMetrics.setDataStallRxTputThrKbps(mDataStallRxTputThrKbps);
        mWifiMetrics.setDataStallTxPerThr(mDataStallTxPerThr);
        mWifiMetrics.setDataStallCcaLevelThr(mDataStallCcaLevelThr);

        mTputSufficientLowThrKbps = DeviceConfig.getInt(NAMESPACE,
                "tput_sufficient_low_thr_kbps", DEFAULT_TPUT_SUFFICIENT_THR_LOW_KBPS);
        mTputSufficientHighThrKbps = DeviceConfig.getInt(NAMESPACE,
                "tput_sufficient_high_thr_kbps", DEFAULT_TPUT_SUFFICIENT_THR_HIGH_KBPS);
        mTputSufficientRatioThrNum = DeviceConfig.getInt(NAMESPACE,
                "tput_sufficient_ratio_thr_num", DEFAULT_TPUT_SUFFICIENT_RATIO_THR_NUM);
        mTputSufficientRatioThrDen = DeviceConfig.getInt(NAMESPACE,
                "tput_sufficient_ratio_thr_den", DEFAULT_TPUT_SUFFICIENT_RATIO_THR_DEN);
        mTxPktPerSecondThr = DeviceConfig.getInt(NAMESPACE,
                "tx_pkt_per_second_thr", DEFAULT_TX_PACKET_PER_SECOND_THR);
        mRxPktPerSecondThr = DeviceConfig.getInt(NAMESPACE,
                "rx_pkt_per_second_thr", DEFAULT_RX_PACKET_PER_SECOND_THR);

        mConnectionFailureHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "connection_failure_high_thr_percent",
                DEFAULT_CONNECTION_FAILURE_HIGH_THR_PERCENT);
        mConnectionFailureCountMin = DeviceConfig.getInt(NAMESPACE,
                "connection_failure_count_min",
                DEFAULT_CONNECTION_FAILURE_COUNT_MIN);
        mAssocRejectionHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_rejection_high_thr_percent",
                DEFAULT_ASSOC_REJECTION_HIGH_THR_PERCENT);
        mAssocRejectionCountMin = DeviceConfig.getInt(NAMESPACE,
                "assoc_rejection_count_min",
                DEFAULT_ASSOC_REJECTION_COUNT_MIN);
        mAssocTimeoutHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_timeout_high_thr_percent",
                DEFAULT_ASSOC_TIMEOUT_HIGH_THR_PERCENT);
        mAssocTimeoutCountMin = DeviceConfig.getInt(NAMESPACE,
                "assoc_timeout_count_min",
                DEFAULT_ASSOC_TIMEOUT_COUNT_MIN);
        mAuthFailureHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "auth_failure_high_thr_percent",
                DEFAULT_AUTH_FAILURE_HIGH_THR_PERCENT);
        mAuthFailureCountMin = DeviceConfig.getInt(NAMESPACE,
                "auth_failure_count_min",
                DEFAULT_AUTH_FAILURE_COUNT_MIN);
        mShortConnectionNonlocalHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "short_connection_nonlocal_high_thr_percent",
                DEFAULT_SHORT_CONNECTION_NONLOCAL_HIGH_THR_PERCENT);
        mShortConnectionNonlocalCountMin = DeviceConfig.getInt(NAMESPACE,
                "short_connection_nonlocal_count_min",
                DEFAULT_SHORT_CONNECTION_NONLOCAL_COUNT_MIN);
        mDisconnectionNonlocalHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "disconnection_nonlocal_high_thr_percent",
                DEFAULT_DISCONNECTION_NONLOCAL_HIGH_THR_PERCENT);
        mDisconnectionNonlocalCountMin = DeviceConfig.getInt(NAMESPACE,
                "disconnection_nonlocal_count_min",
                DEFAULT_DISCONNECTION_NONLOCAL_COUNT_MIN);
        mHealthMonitorRatioThrNumerator = DeviceConfig.getInt(NAMESPACE,
                "health_monitor_ratio_thr_numerator",
                DEFAULT_HEALTH_MONITOR_RATIO_THR_NUMERATOR);
        mHealthMonitorMinRssiThrDbm = DeviceConfig.getInt(NAMESPACE,
                "health_monitor_min_rssi_thr_dbm",
                DEFAULT_HEALTH_MONITOR_MIN_RSSI_THR_DBM);

        mRandomizationFlakySsidHotlist =
                getUnmodifiableSetQuoted("randomization_flaky_ssid_hotlist");
        mAggressiveMacRandomizationSsidAllowlist =
                getUnmodifiableSetQuoted("aggressive_randomization_ssid_allowlist");
        mAggressiveMacRandomizationSsidBlocklist =
                getUnmodifiableSetQuoted("aggressive_randomization_ssid_blocklist");

        mIsAbnormalEapAuthFailureBugreportEnabled = DeviceConfig.getBoolean(NAMESPACE,
                "abnormal_eap_auth_failure_bugreport_enabled", false);
        mHealthMonitorMinNumConnectionAttempt = DeviceConfig.getInt(NAMESPACE,
                "health_monitor_min_num_connection_attempt",
                DEFAULT_HEALTH_MONITOR_MIN_NUM_CONNECTION_ATTEMPT);
    }

    private Set<String> getUnmodifiableSetQuoted(String key) {
        String rawList = DeviceConfig.getString(NAMESPACE, key, "");
        Set<String> result = new ArraySet<>();
        String[] list = rawList.split(",");
        for (String cur : list) {
            if (cur.length() == 0) {
                continue;
            }
            result.add("\"" + cur + "\"");
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Gets the feature flag for reporting abnormally long connections.
     */
    public boolean isAbnormalConnectionBugreportEnabled() {
        return mIsAbnormalConnectionBugreportEnabled;
    }

    /**
     * Gets the threshold for classifying abnormally long connections.
     */
    public int getAbnormalConnectionDurationMs() {
        return mAbnormalConnectionDurationMs;
    }

    /**
     * Gets the duration of evaluating Wifi condition to trigger a data stall.
     */
    public int getDataStallDurationMs() {
        return mDataStallDurationMs;
    }

    /**
     * Gets the threshold of Tx throughput below which to trigger a data stall.
     */
    public int getDataStallTxTputThrKbps() {
        return mDataStallTxTputThrKbps;
    }

    /**
     * Gets the threshold of Rx throughput below which to trigger a data stall.
     */
    public int getDataStallRxTputThrKbps() {
        return mDataStallRxTputThrKbps;
    }

    /**
     * Gets the threshold of Tx packet error rate above which to trigger a data stall.
     */
    public int getDataStallTxPerThr() {
        return mDataStallTxPerThr;
    }

    /**
     * Gets the threshold of CCA level above which to trigger a data stall.
     */
    public int getDataStallCcaLevelThr() {
        return mDataStallCcaLevelThr;
    }

    /**
     * Gets the low threshold of L2 throughput below which L2 throughput is always insufficient
     */
    public int getTputSufficientLowThrKbps() {
        return mTputSufficientLowThrKbps;
    }

    /**
     * Gets the high threshold of L2 throughput above which L2 throughput is always sufficient
     */
    public int getTputSufficientHighThrKbps() {
        return mTputSufficientHighThrKbps;
    }

    /**
     * Gets the numerator part of L2 throughput over L3 throughput ratio sufficiency threshold
     * above which L2 throughput is sufficient
     */
    public int getTputSufficientRatioThrNum() {
        return mTputSufficientRatioThrNum;
    }

    /**
     * Gets the denominator part of L2 throughput over L3 throughput ratio sufficiency threshold
     * above which L2 throughput is sufficient
     */
    public int getTputSufficientRatioThrDen() {
        return mTputSufficientRatioThrDen;
    }

    /**
     * Gets the threshold of Tx packet per second
     * below which Tx throughput sufficiency check will always pass
     */
    public int getTxPktPerSecondThr() {
        return mTxPktPerSecondThr;
    }

    /**
     * Gets the threshold of Rx packet per second
     * below which Rx throughput sufficiency check will always pass
     */
    public int getRxPktPerSecondThr() {
        return mRxPktPerSecondThr;
    }

    /**
     * Gets the high threshold of connection failure rate in percent
     */
    public int getConnectionFailureHighThrPercent() {
        return mConnectionFailureHighThrPercent;
    }

    /**
     * Gets connection failure min count
     */
    public int getConnectionFailureCountMin() {
        return mConnectionFailureCountMin;
    }

    /**
     * Gets the high threshold of association rejection rate in percent
     */
    public int getAssocRejectionHighThrPercent() {
        return mAssocRejectionHighThrPercent;
    }

    /**
     * Gets association rejection min count
     */
    public int getAssocRejectionCountMin() {
        return mAssocRejectionCountMin;
    }

    /**
     * Gets the high threshold of association timeout rate in percent
     */
    public int getAssocTimeoutHighThrPercent() {
        return mAssocTimeoutHighThrPercent;
    }

    /**
     * Gets association timeout min count
     */
    public int getAssocTimeoutCountMin() {
        return mAssocTimeoutCountMin;
    }


    /**
     * Gets the high threshold of authentication failure rate in percent
     */
    public int getAuthFailureHighThrPercent() {
        return mAuthFailureHighThrPercent;
    }

    /**
     * Gets authentication failure min count
     */
    public int getAuthFailureCountMin() {
        return mAuthFailureCountMin;
    }

    /**
     * Gets the high threshold of nonlocal short connection rate in percent
     */
    public int getShortConnectionNonlocalHighThrPercent() {
        return mShortConnectionNonlocalHighThrPercent;
    }

    /**
     * Gets nonlocal short connection min count
     */
    public int getShortConnectionNonlocalCountMin() {
        return mShortConnectionNonlocalCountMin;
    }

    /**
     * Gets the high threshold of nonlocal disconnection rate in percent
     */
    public int getDisconnectionNonlocalHighThrPercent() {
        return mDisconnectionNonlocalHighThrPercent;
    }

    /**
     * Gets nonlocal disconnection min count
     */
    public int getDisconnectionNonlocalCountMin() {
        return mDisconnectionNonlocalCountMin;
    }

    /**
     * Gets health monitor ratio threshold, numerator part
     */
    public int getHealthMonitorRatioThrNumerator() {
        return mHealthMonitorRatioThrNumerator;
    }

    /**
     * Gets health monitor min RSSI threshold in dBm
     */
    public int getHealthMonitorMinRssiThrDbm() {
        return mHealthMonitorMinRssiThrDbm;
    }

    /**
     * Gets the Set of SSIDs in the flaky SSID hotlist.
     */
    public Set<String> getRandomizationFlakySsidHotlist() {
        return mRandomizationFlakySsidHotlist;
    }

    /**
     * Gets the list of SSIDs for aggressive MAC randomization.
     */
    public Set<String> getAggressiveMacRandomizationSsidAllowlist() {
        return mAggressiveMacRandomizationSsidAllowlist;
    }

    /**
     * Gets the list of SSIDs that aggressive MAC randomization should not be used for.
     */
    public Set<String> getAggressiveMacRandomizationSsidBlocklist() {
        return mAggressiveMacRandomizationSsidBlocklist;
    }

    /**
     * Gets the feature flag for reporting abnormal EAP authentication failure.
     */
    public boolean isAbnormalEapAuthFailureBugreportEnabled() {
        return mIsAbnormalEapAuthFailureBugreportEnabled;
    }

    /**
     * Gets health monitor min number of connection attempt threshold
     */
    public int getHealthMonitorMinNumConnectionAttempt() {
        return mHealthMonitorMinNumConnectionAttempt;
    }
}
