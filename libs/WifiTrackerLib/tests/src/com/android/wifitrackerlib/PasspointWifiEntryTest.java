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

package com.android.wifitrackerlib;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Handler;
import android.os.test.TestLooper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class PasspointWifiEntryTest {
    @Mock private Context mMockContext;
    @Mock private WifiManager mMockWifiManager;
    @Mock private Resources mMockResources;

    private TestLooper mTestLooper;
    private Handler mTestHandler;

    private static final String FQDN = "fqdn";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        mTestHandler = new Handler(mTestLooper.getLooper());

        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getString(R.string.summary_separator)).thenReturn("/");
    }

    @Test
    public void testGetSummary_expiredTimeNotAvailable_notShowExpired() {
        // default SubscriptionExpirationTimeInMillis is unset
        PasspointConfiguration passpointConfiguration = getPasspointConfiguration();
        String expired = "Expired";
        when(mMockResources.getString(R.string.wifi_passpoint_expired)).thenReturn(expired);

        PasspointWifiEntry passpointWifiEntry = new PasspointWifiEntry(mMockContext, mTestHandler,
                passpointConfiguration, mMockWifiManager, false /* forSavedNetworksPage */);

        assertThat(passpointWifiEntry.getSummary()).isNotEqualTo(expired);
    }

    @Test
    public void testGetSummary_expired_showExpired() {
        PasspointConfiguration passpointConfiguration = getPasspointConfiguration();
        String expired = "Expired";
        when(mMockResources.getString(R.string.wifi_passpoint_expired)).thenReturn(expired);
        PasspointWifiEntry passpointWifiEntry = new PasspointWifiEntry(mMockContext, mTestHandler,
                passpointConfiguration, mMockWifiManager, false /* forSavedNetworksPage */);
        PasspointWifiEntry spyEntry = spy(passpointWifiEntry);
        when(spyEntry.isExpired()).thenReturn(true);

        assertThat(spyEntry.getSummary()).isEqualTo(expired);
    }

    private PasspointConfiguration getPasspointConfiguration() {
        PasspointConfiguration passpointConfiguration = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(FQDN);
        passpointConfiguration.setHomeSp(homeSp);
        passpointConfiguration.setCredential(new Credential());
        return passpointConfiguration;
    }

    @Test
    public void testGetMeteredChoice_afterSetMeteredChoice_getCorrectValue() {
        PasspointWifiEntry entry = new PasspointWifiEntry(mMockContext, mTestHandler,
                getPasspointConfiguration(), mMockWifiManager, false /* forSavedNetworksPage */);

        entry.setMeteredChoice(WifiEntry.METERED_CHOICE_UNMETERED);

        assertThat(entry.getMeteredChoice()).isEqualTo(WifiEntry.METERED_CHOICE_UNMETERED);
    }

    @Test
    public void testGetSummary_connectedWifiNetwork_showsConnected() {
        String summarySeparator = " / ";
        String[] wifiStatusArray = new String[]{"", "Scanning", "Connecting",
                "Authenticating", "Obtaining IP address", "Connected"};

        Resources mockResources = mock(Resources.class);
        when(mMockContext.getResources()).thenReturn(mockResources);
        when(mockResources.getString(R.string.summary_separator)).thenReturn(summarySeparator);
        when(mockResources.getStringArray(R.array.wifi_status)).thenReturn(wifiStatusArray);
        ConnectivityManager mockConnectivityManager = mock(ConnectivityManager.class);
        when(mMockContext.getSystemService(Context.CONNECTIVITY_SERVICE))
                .thenReturn(mockConnectivityManager);

        WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.isPasspointAp()).thenReturn(true);
        when(wifiInfo.getPasspointFqdn()).thenReturn(FQDN);
        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0 /* subtype */, "WIFI", "");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");

        PasspointWifiEntry entry = new PasspointWifiEntry(mMockContext, mTestHandler,
                getPasspointConfiguration(), mMockWifiManager, false /* forSavedNetworksPage */);
        entry.updateConnectionInfo(wifiInfo, networkInfo);

        assertThat(entry.getSummary()).isEqualTo("Connected");
    }
}
