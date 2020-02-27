/*
 * Copyright 2019 The Android Open Source Project
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

import static android.net.wifi.WifiManager.DEVICE_MOBILITY_STATE_STATIONARY;
import static android.net.wifi.WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;

import android.net.wifi.WifiManager.DeviceMobilityState;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiLinkLayerStats.ChannelStats;
import com.android.server.wifi.util.InformationElementUtil.BssLoad;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * This class collects channel stats over a Wifi Interface
 * and calculates channel utilization using the latest and cached channel stats.
 * Cache saves previous readings of channel stats in a FIFO.
 * The cache is updated when a new stats arrives and it has been a long while since the last update.
 * To get more statistically sound channel utilization, for these devices which support
 * mobility state report, the cache update is stopped when the device stays in the stationary state.
 */
public class WifiChannelUtilization {
    private static final String TAG = "WifiChannelUtilization";
    private static final boolean DBG = false;
    public static final int UNKNOWN_FREQ = -1;
    // Minimum time interval in ms between two cache updates.
    @VisibleForTesting
    static final int DEFAULT_CACHE_UPDATE_INTERVAL_MIN_MS = 10 * 60 * 1000;
    // To get valid channel utilization, the time difference between the reference chanStat's
    // radioOnTime and current chanStat's radioOntime should be no less than the following value
    @VisibleForTesting
    static final int RADIO_ON_TIME_DIFF_MIN_MS = 250;
    // The number of chanStatsMap readings saved in cache
    // where each reading corresponds to one link layer stats update.
    @VisibleForTesting
    static final int CHANNEL_STATS_CACHE_SIZE = 3;
    private final Clock mClock;
    private @DeviceMobilityState int mDeviceMobilityState = DEVICE_MOBILITY_STATE_UNKNOWN;
    private int mCacheUpdateIntervalMinMs = DEFAULT_CACHE_UPDATE_INTERVAL_MIN_MS;

    // Map frequency (key) to utilization ratio (value) with the valid range of
    // [BssLoad.MIN_CHANNEL_UTILIZATION, BssLoad.MAX_CHANNEL_UTILIZATION],
    // where MIN_CHANNEL_UTILIZATION corresponds to ratio 0%
    // and MAX_CHANNEL_UTILIZATION corresponds to ratio 100%
    private SparseIntArray mChannelUtilizationMap = new SparseIntArray();
    private ArrayDeque<SparseArray<ChannelStats>> mChannelStatsMapCache = new ArrayDeque<>();
    private long mLastChannelStatsMapTimeStamp;
    private int mLastChannelStatsMapMobilityState;

    WifiChannelUtilization(Clock clock) {
        mClock = clock;
    }

    /**
     * Initialize internal variables and status after wifi is enabled
     * @param wifiLinkLayerStats The latest wifi link layer stats
     */
    public void init(WifiLinkLayerStats wifiLinkLayerStats) {
        mChannelUtilizationMap.clear();
        mChannelStatsMapCache.clear();
        for (int i = 0; i < (CHANNEL_STATS_CACHE_SIZE - 1); ++i) {
            mChannelStatsMapCache.addFirst(new SparseArray<>());
        }
        if (wifiLinkLayerStats != null) {
            mChannelStatsMapCache.addFirst(wifiLinkLayerStats.channelStatsMap);
        } else {
            mChannelStatsMapCache.addFirst(new SparseArray<>());
        }
        mLastChannelStatsMapTimeStamp = mClock.getElapsedSinceBootMillis();
        if (DBG) {
            Log.d(TAG, "initializing");
        }
    }

    /**
     * Set channel stats cache update minimum interval
     */
    public void setCacheUpdateIntervalMs(int cacheUpdateIntervalMinMs) {
        mCacheUpdateIntervalMinMs = cacheUpdateIntervalMinMs;
    }

    /**
     * Get channel utilization ratio for a given frequency
     * @param frequency The center frequency of 20MHz WLAN channel
     * @return Utilization ratio value if it is available; BssLoad.INVALID otherwise
     */
    public int getUtilizationRatio(int frequency) {
        return mChannelUtilizationMap.get(frequency, BssLoad.INVALID);
    }

    /**
     * Update device mobility state
     * @param newState the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        mDeviceMobilityState = newState;
        if (DBG) {
            Log.d(TAG, " update device mobility state to " + newState);
        }
    }

    /**
     * Set channel utilization ratio for a given frequency
     * @param frequency The center frequency of 20MHz channel
     * @param utilizationRatio The utilization ratio of 20MHz channel
     */
    public void setUtilizationRatio(int frequency, int utilizationRatio) {
        mChannelUtilizationMap.put(frequency, utilizationRatio);
    }

    /**
     * Update channel utilization with the latest link layer stats and the cached channel stats
     * and then update channel stats cache
     * If the given frequency is UNKNOWN_FREQ, calculate channel utilization of all frequencies
     * Otherwise, calculate the channel utilization of the given frequency
     * @param wifiLinkLayerStats The latest wifi link layer stats
     * @param frequency Current frequency of network.
     */
    public void refreshChannelStatsAndChannelUtilization(WifiLinkLayerStats wifiLinkLayerStats,
            int frequency) {
        if (wifiLinkLayerStats == null) {
            return;
        }
        SparseArray<ChannelStats>  channelStatsMap = wifiLinkLayerStats.channelStatsMap;
        if (channelStatsMap == null) {
            return;
        }
        if (frequency != UNKNOWN_FREQ) {
            ChannelStats channelStats = channelStatsMap.get(frequency, null);
            if (channelStats != null) calculateChannelUtilization(channelStats);
        } else {
            for (int i = 0; i < channelStatsMap.size(); i++) {
                ChannelStats channelStats = channelStatsMap.valueAt(i);
                calculateChannelUtilization(channelStats);
            }
        }
        updateChannelStatsCache(channelStatsMap);
    }

    private void calculateChannelUtilization(ChannelStats channelStats) {
        int freq = channelStats.frequency;
        int ccaBusyTimeMs = channelStats.ccaBusyTimeMs;
        int radioOnTimeMs = channelStats.radioOnTimeMs;

        ChannelStats channelStatsRef = findChanStatsReference(freq, radioOnTimeMs);
        int busyTimeDiff = ccaBusyTimeMs - channelStatsRef.ccaBusyTimeMs;
        int radioOnTimeDiff = radioOnTimeMs - channelStatsRef.radioOnTimeMs;
        int utilizationRatio = BssLoad.INVALID;
        if (radioOnTimeDiff >= RADIO_ON_TIME_DIFF_MIN_MS && busyTimeDiff >= 0) {
            utilizationRatio = calculateUtilizationRatio(radioOnTimeDiff, busyTimeDiff);
        }
        mChannelUtilizationMap.put(freq, utilizationRatio);

        if (DBG) {
            int utilizationRatioT0 = calculateUtilizationRatio(radioOnTimeMs, ccaBusyTimeMs);
            Log.d(TAG, " freq: " + freq + " busyTimeDiff: " + busyTimeDiff
                    + " radioOnTimeDiff: " + radioOnTimeDiff
                    + " utilization: " + utilizationRatio
                    + " utilization from time 0: " + utilizationRatioT0);
        }
    }
    /**
     * Find a proper channelStats reference from channelStatsMap cache.
     * The search continues until it finds a channelStat at the given frequency with radioOnTime
     * sufficiently smaller than current radioOnTime, or there is no channelStats for the given
     * frequency or it reaches the end of cache.
     * @param freq Frequency of current channel
     * @param radioOnTimeMs The latest radioOnTime of current channel
     * @return the found channelStat reference if search succeeds, or a dummy channelStats with time
     * zero if channelStats is not found for the given frequency, or a dummy channelStats with the
     * latest radioOnTimeMs if it reaches the end of cache.
     */
    private ChannelStats findChanStatsReference(int freq, int radioOnTimeMs) {
        // A dummy channelStats with the latest radioOnTimeMs.
        ChannelStats channelStatsCurrRadioOnTime = new ChannelStats();
        channelStatsCurrRadioOnTime.radioOnTimeMs = radioOnTimeMs;
        Iterator iterator = mChannelStatsMapCache.iterator();
        while (iterator.hasNext()) {
            SparseArray<ChannelStats> channelStatsMap = (SparseArray<ChannelStats>) iterator.next();
            // If the freq can't be found in current channelStatsMap, stop search because it won't
            // appear in older ones either due to the fact that channelStatsMap are accumulated
            // in HW and thus a recent reading should have channels no less than old readings.
            // Return a dummy channelStats with zero radioOnTimeMs
            if (channelStatsMap == null || channelStatsMap.get(freq) == null) {
                return new ChannelStats();
            }
            ChannelStats channelStats = channelStatsMap.get(freq);
            if (DBG) {
                Log.d(TAG, "freq " + channelStats.frequency + " radioOnTime "
                        + channelStats.radioOnTimeMs + " ccaBusyTime "
                        + channelStats.ccaBusyTimeMs);
            }
            int radioOnTimeDiff = radioOnTimeMs - channelStats.radioOnTimeMs;
            if (radioOnTimeDiff >= RADIO_ON_TIME_DIFF_MIN_MS) {
                return channelStats;
            }
        }
        return channelStatsCurrRadioOnTime;
    }

    private int calculateUtilizationRatio(int radioOnTimeDiff, int busyTimeDiff) {
        int maxRange = BssLoad.MAX_CHANNEL_UTILIZATION - BssLoad.MIN_CHANNEL_UTILIZATION;
        if (radioOnTimeDiff > 0) {
            return (busyTimeDiff * maxRange / radioOnTimeDiff + BssLoad.MIN_CHANNEL_UTILIZATION);
        } else {
            return BssLoad.INVALID;
        }
    }

    private void updateChannelStatsCache(SparseArray<ChannelStats> channelStatsMap) {
        // Don't update cache if the device stays in the stationary state
        if (mLastChannelStatsMapMobilityState == DEVICE_MOBILITY_STATE_STATIONARY
                && mDeviceMobilityState == DEVICE_MOBILITY_STATE_STATIONARY) {
            if (DBG) {
                Log.d(TAG, " skip cache update since device remains in stationary state");
            }
            return;
        }
        // Update cache if it has been a while since the last update
        long currTimeStamp = mClock.getElapsedSinceBootMillis();
        if (DBG) {
            Log.d(TAG, " current time stamp: " + currTimeStamp);
        }
        if ((currTimeStamp - mLastChannelStatsMapTimeStamp) >= mCacheUpdateIntervalMinMs) {
            mChannelStatsMapCache.addFirst(channelStatsMap);
            mChannelStatsMapCache.removeLast();
            mLastChannelStatsMapTimeStamp = currTimeStamp;
            mLastChannelStatsMapMobilityState = mDeviceMobilityState;
        }
    }
}
