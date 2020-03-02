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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.Collections;
import java.util.Set;


/**
 * Unit tests for {@link com.android.server.wifi.DeviceConfigFacade}.
 */
@SmallTest
public class DeviceConfigFacadeTest extends WifiBaseTest {
    @Mock Context mContext;
    @Mock WifiMetrics mWifiMetrics;

    final ArgumentCaptor<OnPropertiesChangedListener> mOnPropertiesChangedListenerCaptor =
            ArgumentCaptor.forClass(OnPropertiesChangedListener.class);

    private DeviceConfigFacade mDeviceConfigFacade;
    private TestLooper mLooper = new TestLooper();
    private MockitoSession mSession;

    /**
     * Setup the mocks and an instance of WifiConfigManager before each test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // static mocking
        mSession = ExtendedMockito.mockitoSession()
                .mockStatic(DeviceConfig.class, withSettings().lenient())
                .startMocking();
        // Have DeviceConfig return the default value passed in.
        when(DeviceConfig.getBoolean(anyString(), anyString(), anyBoolean()))
                .then(new AnswerWithArguments() {
                    public boolean answer(String namespace, String field, boolean def) {
                        return def;
                    }
                });
        when(DeviceConfig.getInt(anyString(), anyString(), anyInt()))
                .then(new AnswerWithArguments() {
                    public int answer(String namespace, String field, int def) {
                        return def;
                    }
                });
        when(DeviceConfig.getString(anyString(), anyString(), anyString()))
                .then(new AnswerWithArguments() {
                    public String answer(String namespace, String field, String def) {
                        return def;
                    }
                });

        mDeviceConfigFacade = new DeviceConfigFacade(mContext, new Handler(mLooper.getLooper()),
                mWifiMetrics);
        verify(() -> DeviceConfig.addOnPropertiesChangedListener(anyString(), any(),
                mOnPropertiesChangedListenerCaptor.capture()));
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
        mSession.finishMocking();
    }

    /**
     * Verifies that default values are set correctly
     */
    @Test
    public void testDefaultValue() throws Exception {
        assertEquals(false, mDeviceConfigFacade.isAbnormalConnectionBugreportEnabled());
        assertEquals(DeviceConfigFacade.DEFAULT_ABNORMAL_CONNECTION_DURATION_MS,
                mDeviceConfigFacade.getAbnormalConnectionDurationMs());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_DURATION_MS,
                mDeviceConfigFacade.getDataStallDurationMs());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS,
                mDeviceConfigFacade.getDataStallTxTputThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS,
                mDeviceConfigFacade.getDataStallRxTputThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_TX_PER_THR,
                mDeviceConfigFacade.getDataStallTxPerThr());
        assertEquals(DeviceConfigFacade.DEFAULT_DATA_STALL_CCA_LEVEL_THR,
                mDeviceConfigFacade.getDataStallCcaLevelThr());
        assertEquals(DeviceConfigFacade.DEFAULT_TPUT_SUFFICIENT_THR_LOW_KBPS,
                mDeviceConfigFacade.getTputSufficientLowThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_TPUT_SUFFICIENT_THR_HIGH_KBPS,
                mDeviceConfigFacade.getTputSufficientHighThrKbps());
        assertEquals(DeviceConfigFacade.DEFAULT_TPUT_SUFFICIENT_RATIO_THR_NUM,
                mDeviceConfigFacade.getTputSufficientRatioThrNum());
        assertEquals(DeviceConfigFacade.DEFAULT_TPUT_SUFFICIENT_RATIO_THR_DEN,
                mDeviceConfigFacade.getTputSufficientRatioThrDen());
        assertEquals(DeviceConfigFacade.DEFAULT_TX_PACKET_PER_SECOND_THR,
                mDeviceConfigFacade.getTxPktPerSecondThr());
        assertEquals(DeviceConfigFacade.DEFAULT_RX_PACKET_PER_SECOND_THR,
                mDeviceConfigFacade.getRxPktPerSecondThr());
        assertEquals(DeviceConfigFacade.DEFAULT_CONNECTION_FAILURE_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getConnectionFailureHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_CONNECTION_FAILURE_LOW_THR_PERCENT,
                mDeviceConfigFacade.getConnectionFailureLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_ASSOC_REJECTION_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getAssocRejectionHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_ASSOC_REJECTION_LOW_THR_PERCENT,
                mDeviceConfigFacade.getAssocRejectionLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_ASSOC_TIMEOUT_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getAssocTimeoutHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_ASSOC_TIMEOUT_LOW_THR_PERCENT,
                mDeviceConfigFacade.getAssocTimeoutLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_AUTH_FAILURE_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getAuthFailureHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_AUTH_FAILURE_LOW_THR_PERCENT,
                mDeviceConfigFacade.getAuthFailureLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_SHORT_CONNECTION_NONLOCAL_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getShortConnectionNonlocalHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_SHORT_CONNECTION_NONLOCAL_LOW_THR_PERCENT,
                mDeviceConfigFacade.getShortConnectionNonlocalLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_DISCONNECTION_NONLOCAL_HIGH_THR_PERCENT,
                mDeviceConfigFacade.getDisconnectionNonlocalHighThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_DISCONNECTION_NONLOCAL_LOW_THR_PERCENT,
                mDeviceConfigFacade.getDisconnectionNonlocalLowThrPercent());
        assertEquals(DeviceConfigFacade.DEFAULT_HEALTH_MONITOR_MIN_RSSI_THR_DBM,
                mDeviceConfigFacade.getHealthMonitorMinRssiThrDbm());
        assertEquals(Collections.emptySet(),
                mDeviceConfigFacade.getRandomizationFlakySsidHotlist());
        assertEquals(Collections.emptySet(),
                mDeviceConfigFacade.getAggressiveMacRandomizationSsidAllowlist());
        assertEquals(Collections.emptySet(),
                mDeviceConfigFacade.getAggressiveMacRandomizationSsidBlocklist());
        assertEquals(false, mDeviceConfigFacade.isAbnormalEapAuthFailureBugreportEnabled());
    }

    /**
     * Verifies that all fields are updated properly.
     */
    @Test
    public void testFieldUpdates() throws Exception {
        // Simulate updating the fields
        when(DeviceConfig.getBoolean(anyString(), eq("abnormal_connection_bugreport_enabled"),
                anyBoolean())).thenReturn(true);
        when(DeviceConfig.getInt(anyString(), eq("abnormal_connection_duration_ms"),
                anyInt())).thenReturn(100);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_duration_ms"),
                anyInt())).thenReturn(0);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_tx_tput_thr_kbps"),
                anyInt())).thenReturn(1000);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_rx_tput_thr_kbps"),
                anyInt())).thenReturn(1500);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_tx_per_thr"),
                anyInt())).thenReturn(95);
        when(DeviceConfig.getInt(anyString(), eq("data_stall_cca_level_thr"),
                anyInt())).thenReturn(80);
        when(DeviceConfig.getInt(anyString(), eq("tput_sufficient_low_thr_kbps"),
                anyInt())).thenReturn(4000);
        when(DeviceConfig.getInt(anyString(), eq("tput_sufficient_high_thr_kbps"),
                anyInt())).thenReturn(8000);
        when(DeviceConfig.getInt(anyString(), eq("tput_sufficient_ratio_thr_num"),
                anyInt())).thenReturn(3);
        when(DeviceConfig.getInt(anyString(), eq("tput_sufficient_ratio_thr_den"),
                anyInt())).thenReturn(2);
        when(DeviceConfig.getInt(anyString(), eq("tx_pkt_per_second_thr"),
                anyInt())).thenReturn(10);
        when(DeviceConfig.getInt(anyString(), eq("rx_pkt_per_second_thr"),
                anyInt())).thenReturn(5);
        when(DeviceConfig.getInt(anyString(), eq("connection_failure_high_thr_percent"),
                anyInt())).thenReturn(31);
        when(DeviceConfig.getInt(anyString(), eq("connection_failure_low_thr_percent"),
                anyInt())).thenReturn(3);
        when(DeviceConfig.getInt(anyString(), eq("assoc_rejection_high_thr_percent"),
                anyInt())).thenReturn(10);
        when(DeviceConfig.getInt(anyString(), eq("assoc_rejection_low_thr_percent"),
                anyInt())).thenReturn(2);
        when(DeviceConfig.getInt(anyString(), eq("assoc_timeout_high_thr_percent"),
                anyInt())).thenReturn(12);
        when(DeviceConfig.getInt(anyString(), eq("assoc_timeout_low_thr_percent"),
                anyInt())).thenReturn(3);
        when(DeviceConfig.getInt(anyString(), eq("auth_failure_high_thr_percent"),
                anyInt())).thenReturn(11);
        when(DeviceConfig.getInt(anyString(), eq("auth_failure_low_thr_percent"),
                anyInt())).thenReturn(2);
        when(DeviceConfig.getInt(anyString(), eq("short_connection_nonlocal_high_thr_percent"),
                anyInt())).thenReturn(8);
        when(DeviceConfig.getInt(anyString(), eq("short_connection_nonlocal_low_thr_percent"),
                anyInt())).thenReturn(1);
        when(DeviceConfig.getInt(anyString(), eq("disconnection_nonlocal_high_thr_percent"),
                anyInt())).thenReturn(12);
        when(DeviceConfig.getInt(anyString(), eq("disconnection_nonlocal_low_thr_percent"),
                anyInt())).thenReturn(2);
        when(DeviceConfig.getInt(anyString(), eq("health_monitor_min_rssi_thr_dbm"),
                anyInt())).thenReturn(-67);
        String testSsidList = "ssid_1,ssid_2";
        when(DeviceConfig.getString(anyString(), eq("randomization_flaky_ssid_hotlist"),
                anyString())).thenReturn(testSsidList);
        when(DeviceConfig.getString(anyString(), eq("aggressive_randomization_ssid_allowlist"),
                anyString())).thenReturn(testSsidList);
        when(DeviceConfig.getString(anyString(), eq("aggressive_randomization_ssid_blocklist"),
                anyString())).thenReturn(testSsidList);
        when(DeviceConfig.getBoolean(anyString(), eq("abnormal_eap_auth_failure_bugreport_enabled"),
                anyBoolean())).thenReturn(true);

        mOnPropertiesChangedListenerCaptor.getValue().onPropertiesChanged(null);

        // Verifying fields are updated to the new values
        Set<String> testSsidSet = new ArraySet<>();
        testSsidSet.add("\"ssid_1\"");
        testSsidSet.add("\"ssid_2\"");
        assertEquals(true, mDeviceConfigFacade.isAbnormalConnectionBugreportEnabled());
        assertEquals(100, mDeviceConfigFacade.getAbnormalConnectionDurationMs());
        assertEquals(0, mDeviceConfigFacade.getDataStallDurationMs());
        assertEquals(1000, mDeviceConfigFacade.getDataStallTxTputThrKbps());
        assertEquals(1500, mDeviceConfigFacade.getDataStallRxTputThrKbps());
        assertEquals(95, mDeviceConfigFacade.getDataStallTxPerThr());
        assertEquals(80, mDeviceConfigFacade.getDataStallCcaLevelThr());
        assertEquals(4000, mDeviceConfigFacade.getTputSufficientLowThrKbps());
        assertEquals(8000, mDeviceConfigFacade.getTputSufficientHighThrKbps());
        assertEquals(3, mDeviceConfigFacade.getTputSufficientRatioThrNum());
        assertEquals(2, mDeviceConfigFacade.getTputSufficientRatioThrDen());
        assertEquals(10, mDeviceConfigFacade.getTxPktPerSecondThr());
        assertEquals(5, mDeviceConfigFacade.getRxPktPerSecondThr());
        assertEquals(31, mDeviceConfigFacade.getConnectionFailureHighThrPercent());
        assertEquals(3, mDeviceConfigFacade.getConnectionFailureLowThrPercent());
        assertEquals(10, mDeviceConfigFacade.getAssocRejectionHighThrPercent());
        assertEquals(2, mDeviceConfigFacade.getAssocRejectionLowThrPercent());
        assertEquals(12, mDeviceConfigFacade.getAssocTimeoutHighThrPercent());
        assertEquals(3, mDeviceConfigFacade.getAssocTimeoutLowThrPercent());
        assertEquals(11, mDeviceConfigFacade.getAuthFailureHighThrPercent());
        assertEquals(2, mDeviceConfigFacade.getAuthFailureLowThrPercent());
        assertEquals(8, mDeviceConfigFacade.getShortConnectionNonlocalHighThrPercent());
        assertEquals(1, mDeviceConfigFacade.getShortConnectionNonlocalLowThrPercent());
        assertEquals(12, mDeviceConfigFacade.getDisconnectionNonlocalHighThrPercent());
        assertEquals(2, mDeviceConfigFacade.getDisconnectionNonlocalLowThrPercent());
        assertEquals(-67, mDeviceConfigFacade.getHealthMonitorMinRssiThrDbm());
        assertEquals(testSsidSet, mDeviceConfigFacade.getRandomizationFlakySsidHotlist());
        assertEquals(testSsidSet,
                mDeviceConfigFacade.getAggressiveMacRandomizationSsidAllowlist());
        assertEquals(testSsidSet,
                mDeviceConfigFacade.getAggressiveMacRandomizationSsidBlocklist());
        assertEquals(true, mDeviceConfigFacade.isAbnormalEapAuthFailureBugreportEnabled());
    }
}
