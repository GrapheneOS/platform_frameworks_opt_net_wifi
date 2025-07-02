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

import android.net.wifi.ScanResult;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility class to keep a running list of scan results merged by SSID+BSSID pair.
 *
 * Thread-safe.
 */
public class ScanResultUpdater {
    private static final long MAX_SCAN_AGE_FOR_FAILED_SCAN_MS = 5 * 60 * 1000;

    private Map<Pair<String, String>, ScanResult> mScanResultsBySsidAndBssid = new ArrayMap<>();
    private final long mMaxScanAgeMillis;
    private final Object mLock = new Object();
    private final Clock mClock;

    /**
     * Creates a ScanResultUpdater with a max scan age in milliseconds. Scans older than this limit
     * will be pruned upon update/retrieval to keep the size of the scan list down.
     */
    public ScanResultUpdater(Clock clock, long maxScanAgeMillis) {
        mMaxScanAgeMillis = maxScanAgeMillis;
        mClock = clock;
    }

    /**
     * Updates the latest scan results, replacing older scans of the same SSID+BSSID pair and
     * removing any scan that is older than the max scan age.
     * Note: To prevent the scan result list from clearing out in the case of temporary consecutive
     * failed scans, avoid clearing old scans upon scan failure as long as they're not older than
     * MAX_SCAN_AGE_FOR_FAILED_SCAN_MS.
     */
    public void onScanResultsAvailable(
            @NonNull List<ScanResult> newResults, boolean timeoutScans) {
        synchronized (mLock) {
            for (ScanResult result : newResults) {
                final Pair<String, String> key = new Pair(result.SSID, result.BSSID);
                ScanResult prevResult = mScanResultsBySsidAndBssid.get(key);
                if (prevResult == null || (prevResult.timestamp < result.timestamp)) {
                    mScanResultsBySsidAndBssid.put(key, result);
                }
            }
            long maxScanAge = timeoutScans ? mMaxScanAgeMillis : MAX_SCAN_AGE_FOR_FAILED_SCAN_MS;
            mScanResultsBySsidAndBssid.entrySet().removeIf((entry) ->
                    mClock.millis() - entry.getValue().timestamp / 1000 > maxScanAge);
        }
    }

    /**
     * Returns the current up-to-date scan results merged by SSID+BSSID pair.
     */
    @NonNull
    public List<ScanResult> getScanResults() throws IllegalArgumentException {
        synchronized (mLock) {
            return new ArrayList<>(mScanResultsBySsidAndBssid.values());
        }
    }
}
