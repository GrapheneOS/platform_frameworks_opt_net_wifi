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

import android.net.wifi.WifiConfiguration;
import android.telephony.SubscriptionManager;

import com.android.server.wifi.util.MissingCounterTimerLockList;

import java.util.Set;

/**
 * Keep track of the disabled duration for all non-carrier-merged networks.
 */
public class NonCarrierMergedNetworksStatusTracker {
    private final Clock mClock;
    private int mSubscriptionId;
    private long mDisableStartTimeMs;
    private long mDisableDurationMs;
    private final MissingCounterTimerLockList<String> mTemporarilyDisabledNonCarrierMergedList;

    public NonCarrierMergedNetworksStatusTracker(Clock clock) {
        mClock = clock;
        mTemporarilyDisabledNonCarrierMergedList =
                new MissingCounterTimerLockList<>(
                        WifiConfigManager.SCAN_RESULT_MISSING_COUNT_THRESHOLD, mClock);
        mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Disable autojoin for all non-carrier-merged networks for the specified duration.
     * @param subscriptionId the subscription ID of the carrier network.
     * @param disableDurationMs the duration in milliseconds this carrier network is selected.
     */
    public void disableAllNonCarrierMergedNetworks(int subscriptionId, long disableDurationMs) {
        mSubscriptionId = subscriptionId;
        mDisableStartTimeMs = mClock.getElapsedSinceBootMillis();
        mDisableDurationMs = disableDurationMs;
    }

    /**
     * Add a SSID or FQDN to the temporary disabled list for the given timer duration. The SSID
     * or FQDN will be re-enabled when after it is out of range for the specified duration.
     */
    public void temporarilyDisableNetwork(WifiConfiguration config, long timerDurationMs) {
        mTemporarilyDisabledNonCarrierMergedList.add(getKeyFromConfig(config), timerDurationMs);
    }

    /**
     * Used to detect whether a disabled network is still in range.
     * A disabled network that does not show up in the list passed in here for |timerDurationMs|
     * will be re-enabled.
     */
    public void update(Set<String> networks) {
        mTemporarilyDisabledNonCarrierMergedList.update(networks);
    }

    /**
     * Re-enable autojoin for all non-carrier-merged networks.
     */
    public void clear() {
        mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mDisableStartTimeMs = 0;
        mDisableDurationMs = 0;
        mTemporarilyDisabledNonCarrierMergedList.clear();
    }

    /**
     * Returns whether the given network should be not allowed for auto-connect.
     * A network could be disable either because all non-carrier-merged networks are not allowed,
     * or this specific network is still in the temporarily disabled list.
     * @param config the network to check whether auto-connect should be disabled on
     */
    public boolean isNetworkDisabled(WifiConfiguration config) {
        // always allow a carrier-merged network with matching subscription ID through.
        if (config.carrierMerged && config.subscriptionId == mSubscriptionId) {
            return false;
        }
        if (shouldDisableAllNonCarrierMergedNetworks()) {
            return true;
        }
        String key = getKeyFromConfig(config);
        if (mTemporarilyDisabledNonCarrierMergedList.isLocked(key)) {
            return true;
        }
        mTemporarilyDisabledNonCarrierMergedList.remove(key);
        return false;
    }

    private String getKeyFromConfig(WifiConfiguration config) {
        return config.isPasspoint() ? config.FQDN : config.SSID;
    }

    private boolean shouldDisableAllNonCarrierMergedNetworks() {
        return mClock.getElapsedSinceBootMillis() - mDisableStartTimeMs < mDisableDurationMs;
    }
}
