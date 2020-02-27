/*
 * Copyright 2018 The Android Open Source Project
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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.server.wifi.proto.WifiStatsLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.scanner.KnownBandsChannelHelper;
import com.android.server.wifi.util.InformationElementUtil.BssLoad;
import com.android.wifi.resources.R;

/**
 * Looks for Wifi data stalls
 */
public class WifiDataStall {
    private static final String TAG = "WifiDataStall";
    private boolean mVerboseLoggingEnabled = false;
    // Maximum time gap between two WifiLinkLayerStats to trigger a data stall
    public static final int MAX_MS_DELTA_FOR_DATA_STALL = 60 * 1000; // 1 minute
    // Maximum time that a data stall start time stays valid.
    public static final long VALIDITY_PERIOD_OF_DATA_STALL_START_MS = 30 * 1000; // 0.5 minutes
    // Default Tx packet error rate when there is no Tx attempt
    public static final int DEFAULT_TX_PACKET_ERROR_RATE = 20;
    // Default CCA level when CCA stats are not available
    public static final int DEFAULT_CCA_LEVEL_2G = CHANNEL_UTILIZATION_SCALE * 16 / 100;
    public static final int DEFAULT_CCA_LEVEL_ABOVE_2G = CHANNEL_UTILIZATION_SCALE * 6 / 100;
    // Minimum time interval in ms between two link layer stats cache updates
    private static final int LLSTATS_CACHE_UPDATE_INTERVAL_MIN_MS = 6 * 1000;
    // Maximum time margin between two link layer stats for connection duration update
    public static final int MAX_TIME_MARGIN_LAST_TWO_POLLS_MS = 200;

    private final FrameworkFacade mFacade;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private final WifiMetrics mWifiMetrics;
    private final Context mContext;
    private final WifiChannelUtilization mWifiChannelUtilization;
    private TelephonyManager mTelephonyManager;

    private int mLastFrequency = -1;
    private String mLastBssid;
    private long mDataStallStartTimeMs = -1;
    private Clock mClock;
    private boolean mDataStallTx = false;
    private boolean mDataStallRx = false;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private boolean mIsThroughputSufficient = true;
    private boolean mIsCellularDataAvailable = false;
    private final PhoneStateListener mPhoneStateListener;
    private boolean mPhoneStateListenerEnabled = false;

    public WifiDataStall(FrameworkFacade facade, WifiMetrics wifiMetrics, Context context,
            DeviceConfigFacade deviceConfigFacade, WifiChannelUtilization wifiChannelUtilization,
            Clock clock, Handler handler) {
        mFacade = facade;
        mDeviceConfigFacade = deviceConfigFacade;
        mWifiMetrics = wifiMetrics;
        mContext = context;
        mClock = clock;
        mWifiChannelUtilization = wifiChannelUtilization;
        mWifiChannelUtilization.init(null);
        mWifiChannelUtilization.setCacheUpdateIntervalMs(LLSTATS_CACHE_UPDATE_INTERVAL_MIN_MS);
        mPhoneStateListener = new PhoneStateListener(new HandlerExecutor(handler)) {
            @Override
            public void onDataConnectionStateChanged(int state, int networkType) {
                if (state == TelephonyManager.DATA_CONNECTED) {
                    mIsCellularDataAvailable = true;
                } else if (state == TelephonyManager.DATA_DISCONNECTED) {
                    mIsCellularDataAvailable = false;
                } else {
                    Log.e(TAG, "onDataConnectionStateChanged unexpected State: " + state);
                    return;
                }
                logd("Cellular Data: " + mIsCellularDataAvailable);
            }
        };
    }

    /**
     * Enable phone state listener
     */
    public void enablePhoneStateListener() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager) mContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
        }
        if (mTelephonyManager != null && !mPhoneStateListenerEnabled) {
            mPhoneStateListenerEnabled = true;
            mTelephonyManager.listen(mPhoneStateListener,
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
        }
    }

    /**
     * Disable phone state listener
     */
    public void disablePhoneStateListener() {
        if (mTelephonyManager != null && mPhoneStateListenerEnabled) {
            mPhoneStateListenerEnabled = false;
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    /**
     * Enable/Disable verbose logging.
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Update device mobility state
     * @param newState the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        mWifiChannelUtilization.setDeviceMobilityState(newState);
    }

    /**
     * Check if current link layer throughput is sufficient.
     * This should be called after checkDataStallAndThroughputSufficiency().
     * @return true if it is sufficient or false if it is insufficient
     */
    public boolean isThroughputSufficient() {
        return mIsThroughputSufficient;
    }

    /**
     * Check if cellular data is available
     * @return true if it is available and false otherwise
     */
    public boolean isCellularDataAvailable() {
        return mIsCellularDataAvailable;
    }

    /**
     * Update data stall detection, check throughput sufficiency and report wifi health stat
     * with the latest link layer stats
     * @param oldStats second most recent WifiLinkLayerStats
     * @param newStats most recent WifiLinkLayerStats
     * @param wifiInfo WifiInfo for current connection
     * @return trigger type of WifiIsUnusableEvent
     */
    public int checkDataStallAndThroughputSufficiency(WifiLinkLayerStats oldStats,
            WifiLinkLayerStats newStats, WifiInfo wifiInfo) {
        int currFrequency = wifiInfo.getFrequency();
        mWifiChannelUtilization.refreshChannelStatsAndChannelUtilization(newStats, currFrequency);

        if (oldStats == null || newStats == null) {
            mWifiMetrics.resetWifiIsUnusableLinkLayerStats();
            mIsThroughputSufficient = true;
            return WifiIsUnusableEvent.TYPE_UNKNOWN;
        }

        long txSuccessDelta = (newStats.txmpdu_be + newStats.txmpdu_bk
                + newStats.txmpdu_vi + newStats.txmpdu_vo)
                - (oldStats.txmpdu_be + oldStats.txmpdu_bk
                + oldStats.txmpdu_vi + oldStats.txmpdu_vo);
        long txRetriesDelta = (newStats.retries_be + newStats.retries_bk
                + newStats.retries_vi + newStats.retries_vo)
                - (oldStats.retries_be + oldStats.retries_bk
                + oldStats.retries_vi + oldStats.retries_vo);
        long txBadDelta = (newStats.lostmpdu_be + newStats.lostmpdu_bk
                + newStats.lostmpdu_vi + newStats.lostmpdu_vo)
                - (oldStats.lostmpdu_be + oldStats.lostmpdu_bk
                + oldStats.lostmpdu_vi + oldStats.lostmpdu_vo);
        long rxSuccessDelta = (newStats.rxmpdu_be + newStats.rxmpdu_bk
                + newStats.rxmpdu_vi + newStats.rxmpdu_vo)
                - (oldStats.rxmpdu_be + oldStats.rxmpdu_bk
                + oldStats.rxmpdu_vi + oldStats.rxmpdu_vo);
        int timeDeltaLastTwoPollsMs = (int) (newStats.timeStampInMs - oldStats.timeStampInMs);

        long totalTxDelta = txSuccessDelta + txRetriesDelta;
        boolean isTxTrafficHigh = (totalTxDelta * 1000)
                > (mDeviceConfigFacade.getTxPktPerSecondThr() * timeDeltaLastTwoPollsMs);
        boolean isRxTrafficHigh = (rxSuccessDelta * 1000)
                > (mDeviceConfigFacade.getTxPktPerSecondThr() * timeDeltaLastTwoPollsMs);
        if (timeDeltaLastTwoPollsMs < 0
                || txSuccessDelta < 0
                || txRetriesDelta < 0
                || txBadDelta < 0
                || rxSuccessDelta < 0) {
            // There was a reset in WifiLinkLayerStats
            mIsThroughputSufficient = true;
            mWifiMetrics.resetWifiIsUnusableLinkLayerStats();
            return WifiIsUnusableEvent.TYPE_UNKNOWN;
        }

        mWifiMetrics.updateWifiIsUnusableLinkLayerStats(txSuccessDelta, txRetriesDelta,
                txBadDelta, rxSuccessDelta, timeDeltaLastTwoPollsMs);

        int txLinkSpeedMbps = wifiInfo.getLinkSpeed();
        int rxLinkSpeedMbps = wifiInfo.getRxLinkSpeedMbps();
        boolean isSameBssidAndFreq = mLastBssid == null || mLastFrequency == -1
                || (mLastBssid.equals(wifiInfo.getBSSID())
                && mLastFrequency == currFrequency);
        mLastFrequency = currFrequency;
        mLastBssid = wifiInfo.getBSSID();

        int ccaLevel = mWifiChannelUtilization.getUtilizationRatio(currFrequency);
        if (ccaLevel == BssLoad.INVALID) {
            ccaLevel = wifiInfo.is24GHz() ? DEFAULT_CCA_LEVEL_2G : DEFAULT_CCA_LEVEL_ABOVE_2G;
            logd(" use default cca Level");
        }
        logd(" ccaLevel = " + ccaLevel);

        int txPer = updateTxPer(txSuccessDelta, txRetriesDelta, isSameBssidAndFreq);

        boolean isTxTputLow = false;
        boolean isRxTputLow = false;
        int txTputKbps = 0;

        if (txLinkSpeedMbps > 0) {
            long temp = (long) txLinkSpeedMbps * (1000 * (100 - txPer) / 100)
                    * (CHANNEL_UTILIZATION_SCALE  - ccaLevel) / CHANNEL_UTILIZATION_SCALE;
            txTputKbps = (int) temp;
            isTxTputLow =  txTputKbps < mDeviceConfigFacade.getDataStallTxTputThrKbps();
        }
        int rxTputKbps = 0;
        if (rxLinkSpeedMbps > 0) {
            long temp = (long) rxLinkSpeedMbps * 1000 * (CHANNEL_UTILIZATION_SCALE  - ccaLevel)
                    / CHANNEL_UTILIZATION_SCALE;
            rxTputKbps = (int) temp;
            isRxTputLow = rxTputKbps < mDeviceConfigFacade.getDataStallRxTputThrKbps();
        }

        mIsThroughputSufficient = isThroughputSufficientInternal(txTputKbps, rxTputKbps,
                isTxTrafficHigh, isRxTrafficHigh, timeDeltaLastTwoPollsMs);

        int maxTimeDeltaMs = mContext.getResources().getInteger(
                R.integer.config_wifiPollRssiIntervalMilliseconds)
                + MAX_TIME_MARGIN_LAST_TWO_POLLS_MS;
        if (timeDeltaLastTwoPollsMs > 0 && timeDeltaLastTwoPollsMs <= maxTimeDeltaMs) {
            mWifiMetrics.incrementConnectionDuration(timeDeltaLastTwoPollsMs,
                    mIsThroughputSufficient, mIsCellularDataAvailable);
            reportWifiHealthStat(currFrequency, timeDeltaLastTwoPollsMs, mIsThroughputSufficient,
                    mIsCellularDataAvailable);
        }

        boolean possibleDataStallTx = isTxTputLow
                || ccaLevel >= mDeviceConfigFacade.getDataStallCcaLevelThr()
                || txPer >= mDeviceConfigFacade.getDataStallTxPerThr();
        boolean possibleDataStallRx = isRxTputLow
                || ccaLevel >= mDeviceConfigFacade.getDataStallCcaLevelThr();

        boolean dataStallTx = isTxTrafficHigh ? possibleDataStallTx : mDataStallTx;
        boolean dataStallRx = isRxTrafficHigh ? possibleDataStallRx : mDataStallRx;

        return detectConsecutiveTwoDataStalls(timeDeltaLastTwoPollsMs, dataStallTx, dataStallRx);
    }

    // Data stall event is triggered if there are consecutive Tx and/or Rx data stalls
    // 1st data stall should be preceded by no data stall
    // Reset mDataStallStartTimeMs to -1 if currently there is no Tx or Rx data stall
    private int detectConsecutiveTwoDataStalls(int timeDeltaLastTwoPollsMs,
            boolean dataStallTx, boolean dataStallRx) {
        if (timeDeltaLastTwoPollsMs >= MAX_MS_DELTA_FOR_DATA_STALL) {
            return WifiIsUnusableEvent.TYPE_UNKNOWN;
        }

        if (dataStallTx || dataStallRx) {
            mDataStallTx = mDataStallTx || dataStallTx;
            mDataStallRx = mDataStallRx || dataStallRx;
            if (mDataStallStartTimeMs == -1) {
                mDataStallStartTimeMs = mClock.getElapsedSinceBootMillis();
                if (mDeviceConfigFacade.getDataStallDurationMs() == 0) {
                    mDataStallStartTimeMs = -1;
                    int result = calculateUsabilityEventType(mDataStallTx, mDataStallRx);
                    mDataStallRx = false;
                    mDataStallTx = false;
                    return result;
                }
            } else {
                long elapsedTime = mClock.getElapsedSinceBootMillis() - mDataStallStartTimeMs;
                if (elapsedTime >= mDeviceConfigFacade.getDataStallDurationMs()) {
                    mDataStallStartTimeMs = -1;
                    if (elapsedTime <= VALIDITY_PERIOD_OF_DATA_STALL_START_MS) {
                        int result = calculateUsabilityEventType(mDataStallTx, mDataStallRx);
                        mDataStallRx = false;
                        mDataStallTx = false;
                        return result;
                    } else {
                        mDataStallTx = false;
                        mDataStallRx = false;
                    }
                } else {
                    // No need to do anything.
                }
            }
        } else {
            mDataStallStartTimeMs = -1;
            mDataStallTx = false;
            mDataStallRx = false;
        }
        return WifiIsUnusableEvent.TYPE_UNKNOWN;
    }

    private int updateTxPer(long txSuccessDelta, long txRetriesDelta, boolean isSameBssidAndFreq) {
        if (!isSameBssidAndFreq) {
            return DEFAULT_TX_PACKET_ERROR_RATE;
        }
        long txAttempts = txSuccessDelta + txRetriesDelta;
        if (txAttempts <= 0) {
            return DEFAULT_TX_PACKET_ERROR_RATE;
        }
        return (int) (txRetriesDelta * 100 / txAttempts);
    }
    private int calculateUsabilityEventType(boolean dataStallTx, boolean dataStallRx) {
        int result = WifiIsUnusableEvent.TYPE_UNKNOWN;
        if (dataStallTx && dataStallRx) {
            result = WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH;
        } else if (dataStallTx) {
            result = WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX;
        } else if (dataStallRx) {
            result = WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX;
        }
        mWifiMetrics.logWifiIsUnusableEvent(result);
        return result;
    }

    private boolean isThroughputSufficientInternal(int l2TxTputKbps, int l2RxTputKbps,
            boolean isTxTrafficHigh, boolean isRxTrafficHigh, int timeDeltaLastTwoPollsMs) {
        long txBytes = mFacade.getTotalTxBytes() - mFacade.getMobileTxBytes();
        long rxBytes = mFacade.getTotalRxBytes() - mFacade.getMobileRxBytes();
        if (timeDeltaLastTwoPollsMs > MAX_MS_DELTA_FOR_DATA_STALL
                || mLastTxBytes == 0 || mLastRxBytes == 0) {
            mLastTxBytes = txBytes;
            mLastRxBytes = rxBytes;
            return true;
        }

        int l3TxTputKbps = (int) ((txBytes - mLastTxBytes) * 8 / timeDeltaLastTwoPollsMs);
        int l3RxTputKbps = (int) ((rxBytes - mLastRxBytes) * 8 / timeDeltaLastTwoPollsMs);

        mLastTxBytes = txBytes;
        mLastRxBytes = rxBytes;

        boolean isTxTputSufficient = isL2ThroughputSufficient(l2TxTputKbps, l3TxTputKbps);
        boolean isRxTputSufficient = isL2ThroughputSufficient(l2RxTputKbps, l3RxTputKbps);
        isTxTputSufficient = detectAndOverrideFalseInSufficient(
                isTxTputSufficient, isTxTrafficHigh, mIsThroughputSufficient);
        isRxTputSufficient = detectAndOverrideFalseInSufficient(
                isRxTputSufficient, isRxTrafficHigh, mIsThroughputSufficient);

        boolean isThroughputSufficient = isTxTputSufficient && isRxTputSufficient;

        StringBuilder sb = new StringBuilder();
        logd(sb.append("L2 txTputKbps: ").append(l2TxTputKbps)
                .append(", rxTputKbps: ").append(l2RxTputKbps)
                .append(", L3 txTputKbps: ").append(l3TxTputKbps)
                .append(", rxTputKbps: ").append(l3RxTputKbps)
                .append(", TxTrafficHigh: ").append(isTxTrafficHigh)
                .append(", RxTrafficHigh: ").append(isRxTrafficHigh)
                .append(", Throughput Sufficient: ").append(isThroughputSufficient)
                .toString());
        return isThroughputSufficient;
    }

    /**
     * L2 tput is sufficient when one of the following conditions is met
     * 1) L3 tput is low and L2 tput is above its low threshold
     * 2) L3 tput is not low and L2 tput over L3 tput ratio is above sufficientRatioThr
     * 3) L3 tput is not low and L2 tput is above its high threshold
     */
    private boolean isL2ThroughputSufficient(int l2TputKbps, int l3TputKbps) {
        boolean isL3TputLow = (l3TputKbps * mDeviceConfigFacade.getTputSufficientRatioThrDen())
                < (mDeviceConfigFacade.getTputSufficientLowThrKbps()
                * mDeviceConfigFacade.getTputSufficientRatioThrNum());
        boolean isL2TputAboveLowThr =
                l2TputKbps >= mDeviceConfigFacade.getTputSufficientLowThrKbps();
        if (isL3TputLow) return isL2TputAboveLowThr;

        boolean isL2TputAboveHighThr =
                l2TputKbps >= mDeviceConfigFacade.getTputSufficientHighThrKbps();
        boolean isL2L3TputRatioAboveThr =
                (l2TputKbps * mDeviceConfigFacade.getTputSufficientRatioThrDen())
                >= (l3TputKbps * mDeviceConfigFacade.getTputSufficientRatioThrNum());
        return isL2TputAboveHighThr || isL2L3TputRatioAboveThr;
    }

    private boolean detectAndOverrideFalseInSufficient(boolean isTputSufficient,
            boolean isTrafficHigh, boolean lastIsTputSufficient) {
        boolean possibleFalseInsufficient = (!isTrafficHigh && !isTputSufficient);
        return  possibleFalseInsufficient ? lastIsTputSufficient : isTputSufficient;
    }

    /**
     * Report the latest Wifi connection health to WestWorld
     */
    private void reportWifiHealthStat(int frequency, int timeDeltaLastTwoPollsMs,
            boolean isThroughputSufficient,
            boolean isCellularDataAvailable) {
        int band = getBand(frequency);
        WifiStatsLog.write(WifiStatsLog.WIFI_HEALTH_STAT_REPORTED, timeDeltaLastTwoPollsMs,
                isThroughputSufficient,  isCellularDataAvailable, band);
    }

    private int getBand(int frequency) {
        int band;
        if (frequency >= KnownBandsChannelHelper.BAND_24_GHZ_START_FREQ
                && frequency <= KnownBandsChannelHelper.BAND_24_GHZ_END_FREQ) {
            band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_2G;
        } else if (frequency >= KnownBandsChannelHelper.BAND_5_GHZ_START_FREQ
                && frequency <= KnownBandsChannelHelper.BAND_6_GHZ_END_FREQ) {
            if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_LOW_END_FREQ) {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_5G_LOW;
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_MID_END_FREQ) {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_5G_MIDDLE;
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_END_FREQ) {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_5G_HIGH;
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_LOW_END_FREQ) {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_6G_LOW;
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_MID_END_FREQ) {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_6G_MIDDLE;
            } else {
                band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__BAND_6G_HIGH;
            }
        } else {
            band = WifiStatsLog.WIFI_HEALTH_STAT_REPORTED__BAND__UNKNOWN;
        }
        return band;
    }
    private void logd(String string) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, string);
        }
    }
}
