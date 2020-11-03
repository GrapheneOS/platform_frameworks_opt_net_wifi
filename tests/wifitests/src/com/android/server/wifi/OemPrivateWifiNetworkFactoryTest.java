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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.OemPrivateWifiNetworkFactory}.
 */
@SmallTest
public class OemPrivateWifiNetworkFactoryTest extends WifiBaseTest {
    private static final int TEST_UID = 4556;
    private static final String TEST_PACKAGE_NAME = "com.test";
    private static final WorkSource TEST_WORKSOURCE = new WorkSource(TEST_UID, TEST_PACKAGE_NAME);

    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock Context mContext;
    NetworkCapabilities mNetworkCapabilities;
    TestLooper mLooper;
    NetworkRequest mNetworkRequest;

    private OemPrivateWifiNetworkFactory mOemPrivateWifiNetworkFactory;

    /**
     * Setup the mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new TestLooper();
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE);
        mNetworkCapabilities.setRequestorUid(TEST_UID);
        mNetworkCapabilities.setRequestorPackageName(TEST_PACKAGE_NAME);

        mOemPrivateWifiNetworkFactory = new OemPrivateWifiNetworkFactory(
                mLooper.getLooper(), mContext,
                mNetworkCapabilities, mWifiConnectivityManager);

        mNetworkRequest = new NetworkRequest.Builder()
                .setCapabilities(mNetworkCapabilities)
                .build();
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Validates handling of needNetworkFor.
     */
    @Test
    public void testHandleNetworkRequest() {
        assertFalse(mOemPrivateWifiNetworkFactory.hasConnectionRequests());
        mOemPrivateWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);

        // First network request should turn on auto-join.
        verify(mWifiConnectivityManager).setOemPrivateConnectionAllowed(true, TEST_WORKSOURCE);
        assertTrue(mOemPrivateWifiNetworkFactory.hasConnectionRequests());

        // Subsequent ones should do nothing.
        mOemPrivateWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);
        verifyNoMoreInteractions(mWifiConnectivityManager);
    }

    /**
     * Validates handling of releaseNetwork.
     */
    @Test
    public void testHandleNetworkRelease() {
        // Release network without a corresponding request should be ignored.
        mOemPrivateWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        assertFalse(mOemPrivateWifiNetworkFactory.hasConnectionRequests());

        // Now request & then release the network request
        mOemPrivateWifiNetworkFactory.needNetworkFor(mNetworkRequest, 0);
        assertTrue(mOemPrivateWifiNetworkFactory.hasConnectionRequests());
        verify(mWifiConnectivityManager).setOemPrivateConnectionAllowed(true, TEST_WORKSOURCE);

        mOemPrivateWifiNetworkFactory.releaseNetworkFor(mNetworkRequest);
        assertFalse(mOemPrivateWifiNetworkFactory.hasConnectionRequests());
        verify(mWifiConnectivityManager).setOemPrivateConnectionAllowed(false, null);
    }
}
