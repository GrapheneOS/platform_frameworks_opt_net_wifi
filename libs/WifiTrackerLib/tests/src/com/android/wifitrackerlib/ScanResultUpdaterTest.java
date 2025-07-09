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

import static com.android.wifitrackerlib.TestUtils.buildScanResult;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;

public class ScanResultUpdaterTest {
    private static final String SSID = "ssid";
    private static final String BSSID_1 = "11:11:11:11:11:11";
    private static final String BSSID_2 = "22:22:22:22:22:22";
    private static final String BSSID_3 = "33:33:33:33:33:33";
    private static final long TEST_START_TIME_MS = 123_456_789;
    private static final long TEST_MAX_SCAN_AGE_MS = 15_000;

    @Mock private Clock mMockClock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS);
    }

    /**
     * Verify that scan results of the same BSSID are merged to latest one.
     */
    @Test
    public void testOnScanResultsAvailable_sameSsidBssidUpdatedMultipleTimes_latestScanReturned() {
        ScanResult oldScan = buildScanResult(SSID, BSSID_1, TEST_START_TIME_MS);
        ScanResult newScan = buildScanResult(SSID, BSSID_1, TEST_START_TIME_MS + 10);

        // Add initial scan result. List should have 1 scan.
        ScanResultUpdater sru = new ScanResultUpdater(mMockClock, TEST_MAX_SCAN_AGE_MS);
        sru.onScanResultsAvailable(Arrays.asList(oldScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(oldScan);

        // Add new scan result. Old scan result should be replaced.
        sru.onScanResultsAvailable(Arrays.asList(newScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(newScan);

        // Add old scan result back. New scan result should still remain.
        sru.onScanResultsAvailable(Arrays.asList(oldScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(newScan);
    }

    /**
     * Verify that scan results are filtered out by age.
     */
    @Test
    public void testOnScanResultsAvailable_filtersOldScans() {
        ScanResultUpdater sru = new ScanResultUpdater(mMockClock, TEST_MAX_SCAN_AGE_MS);

        // Add a scan result and a slightly newer scan result.
        ScanResult olderScan = buildScanResult(SSID, BSSID_1, TEST_START_TIME_MS - 1);
        ScanResult newerScan = buildScanResult(SSID, BSSID_2, TEST_START_TIME_MS);
        sru.onScanResultsAvailable(Arrays.asList(olderScan, newerScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(olderScan, newerScan);

        // Age the older result and verify the newer one remains.
        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS + TEST_MAX_SCAN_AGE_MS);
        sru.onScanResultsAvailable(Arrays.asList(olderScan, newerScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(newerScan);

        // Age both results out and verify no results remain.
        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS + TEST_MAX_SCAN_AGE_MS + 1);
        sru.onScanResultsAvailable(Arrays.asList(olderScan, newerScan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).isEmpty();
    }

    /**
     * Verify that scan results are not aged out if timeoutScans is false.
     */
    @Test
    public void testOnScanResultsAvailable_multipleScanFailure_scanNotAgedOut() {
        ScanResultUpdater sru = new ScanResultUpdater(mMockClock, TEST_MAX_SCAN_AGE_MS);

        // Add scan result. List should have 1 scan.
        ScanResult scan = buildScanResult(SSID, BSSID_1, TEST_START_TIME_MS);
        sru.onScanResultsAvailable(Arrays.asList(scan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(scan);

        // Failing the scan result should not remove the scan
        sru.onScanResultsAvailable(Collections.emptyList(), false /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(scan);

        // Failing the scan result after the max scan age should not remove the scan
        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS + TEST_MAX_SCAN_AGE_MS + 1);
        sru.onScanResultsAvailable(Collections.emptyList(), false /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(scan);

        // Failing the scan result after the max failed scan age should finally remove the scan.
        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS + (5 * 60 * 1000) + 1);
        sru.onScanResultsAvailable(Collections.emptyList(), false /* timeoutScans */);
        assertThat(sru.getScanResults()).isEmpty();
    }

    /**
     * Verify that old scan results that were not aged out due to scan failure are aged out upon
     * the first scan success.
     */
    @Test
    public void testOnScanResultsAvailable_scanSuccessAfterFailure_scanAgedOut() {
        ScanResultUpdater sru = new ScanResultUpdater(mMockClock, TEST_MAX_SCAN_AGE_MS);

        // Add scan result. List should have 1 scan.
        ScanResult scan = buildScanResult(SSID, BSSID_1, TEST_START_TIME_MS);
        sru.onScanResultsAvailable(Arrays.asList(scan), true /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(scan);

        // Failing the scan result after the max scan age should not remove the scan
        when(mMockClock.millis()).thenReturn(TEST_START_TIME_MS + TEST_MAX_SCAN_AGE_MS + 1);
        sru.onScanResultsAvailable(Collections.emptyList(), false /* timeoutScans */);
        assertThat(sru.getScanResults()).containsExactly(scan);

        // Successful scan should use the max scan age to remove the old scan.
        sru.onScanResultsAvailable(Collections.emptyList(), true /* timeoutScans */);
        assertThat(sru.getScanResults()).isEmpty();
    }
}
