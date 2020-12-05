/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.wifi.coex;


import static android.net.wifi.WifiManager.COEX_RESTRICTION_SOFTAP;
import static android.net.wifi.WifiManager.COEX_RESTRICTION_WIFI_AWARE;
import static android.net.wifi.WifiManager.COEX_RESTRICTION_WIFI_DIRECT;
import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.CoexUnsafeChannel;
import android.net.wifi.ICoexCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiBaseTest;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;


/**
 * Unit tests for {@link com.android.server.wifi.coex.CoexManager}.
 */
@SmallTest
public class CoexManagerTest extends WifiBaseTest {
    private TestLooper mTestLooper;

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private TelephonyManager mMockTelephonyManager;

    private CoexManager createCoexManager() {
        return new CoexManager(mMockContext, mMockTelephonyManager,
                new Handler(mTestLooper.getLooper()));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled))
                .thenReturn(true);
        mTestLooper = new TestLooper();
    }

    /**
     * Verifies that setCoexUnsafeChannels(Set, int) sets values returned in the getter methods
     * getCoexUnsafeChannels() and getCoexRestrictions().
     */
    @Test
    public void testSetCoexUnsafeChannels_nonNullChannels_returnedInGetters() {
        CoexManager coexManager = createCoexManager();
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        final int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;

        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactlyElementsIn(unsafeChannels);
        assertThat(coexManager.getCoexRestrictions()).isEqualTo(restrictions);
    }

    /**
     * Verifies that setCoexUnsafeChannels(Set, int) with an null set results in no change to the
     * current CoexUnsafeChannels or restrictions
     */
    @Test
    public void testSetCoexUnsafeChannels_nullChannels_setsEmptySet() {
        CoexManager coexManager = createCoexManager();
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        final int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;
        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        coexManager.setCoexUnsafeChannels(null, 0);

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactlyElementsIn(unsafeChannels);
        assertThat(coexManager.getCoexRestrictions()).isEqualTo(restrictions);
    }

    /**
     * Verifies that setCoexUnsafeChannels(Set, int) with undefined restriction flags results in no
     * change to the current CoexUnsafeChannels or restrictions
     */
    @Test
    public void testSetCoexUnsafeChannels_undefinedRestrictions_setsEmptySet() {
        CoexManager coexManager = createCoexManager();
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        final int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;
        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        coexManager.setCoexUnsafeChannels(new HashSet<>(), ~restrictions);

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactlyElementsIn(unsafeChannels);
        assertThat(coexManager.getCoexRestrictions()).isEqualTo(restrictions);
    }

    /**
     * Verifies that the registered CoexListeners are notified when
     * setCoexUnsafeChannels is called.
     */
    @Test
    public void testRegisteredCoexListener_setCoexUnsafeChannels_listenerIsNotified() {
        CoexManager.CoexListener listener1 = Mockito.mock(CoexManager.CoexListener.class);
        CoexManager.CoexListener listener2 = Mockito.mock(CoexManager.CoexListener.class);
        CoexManager coexManager = createCoexManager();
        coexManager.registerCoexListener(listener1);
        coexManager.registerCoexListener(listener2);
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;

        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        verify(listener1).onCoexUnsafeChannelsChanged();
        verify(listener2).onCoexUnsafeChannelsChanged();
    }

    /**
     * Verifies that unregistered CoexListeners are not notified when
     * setCoexUnsafeChannels is called.
     */
    @Test
    public void testUnregisteredCoexListener_setCoexUnsafeChannels_listenerIsNotNotified() {
        CoexManager.CoexListener listener1 = Mockito.mock(CoexManager.CoexListener.class);
        CoexManager.CoexListener listener2 = Mockito.mock(CoexManager.CoexListener.class);
        CoexManager coexManager = createCoexManager();
        coexManager.registerCoexListener(listener1);
        coexManager.registerCoexListener(listener2);
        coexManager.unregisterCoexListener(listener1);
        coexManager.unregisterCoexListener(listener2);
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;

        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        verify(listener1, times(0)).onCoexUnsafeChannelsChanged();
        verify(listener2, times(0)).onCoexUnsafeChannelsChanged();
    }

    /**
     * Verifies that registered remote CoexCallbacks are notified when
     * setCoexUnsafeChannels is called.
     */
    @Test
    public void testRegisteredRemoteCoexCallback_setCoexUnsafeChannels_callbackIsNotified()
            throws RemoteException {
        ICoexCallback remoteCallback1 = Mockito.mock(ICoexCallback.class);
        when(remoteCallback1.asBinder()).thenReturn(Mockito.mock(IBinder.class));
        ICoexCallback remoteCallback2 = Mockito.mock(ICoexCallback.class);
        when(remoteCallback2.asBinder()).thenReturn(Mockito.mock(IBinder.class));
        CoexManager coexManager = createCoexManager();
        coexManager.registerRemoteCoexCallback(remoteCallback1);
        coexManager.registerRemoteCoexCallback(remoteCallback2);
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;

        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        verify(remoteCallback1).onCoexUnsafeChannelsChanged();
        verify(remoteCallback2).onCoexUnsafeChannelsChanged();
    }

    /**
     * Verifies that unregistered remote CoexCallbacks are not notified when
     * setCoexUnsafeChannels is called.
     */
    @Test
    public void testUnregisteredRemoteCoexCallback_setCoexUnsafeChannels_callbackIsNotNotified()
            throws RemoteException {
        ICoexCallback remoteCallback1 = Mockito.mock(ICoexCallback.class);
        when(remoteCallback1.asBinder()).thenReturn(Mockito.mock(IBinder.class));
        ICoexCallback remoteCallback2 = Mockito.mock(ICoexCallback.class);
        when(remoteCallback2.asBinder()).thenReturn(Mockito.mock(IBinder.class));
        CoexManager coexManager = createCoexManager();
        coexManager.registerRemoteCoexCallback(remoteCallback1);
        coexManager.registerRemoteCoexCallback(remoteCallback2);
        coexManager.unregisterRemoteCoexCallback(remoteCallback1);
        coexManager.unregisterRemoteCoexCallback(remoteCallback2);
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36));
        int restrictions = COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE;

        coexManager.setCoexUnsafeChannels(unsafeChannels, restrictions);

        verify(remoteCallback1, times(0)).onCoexUnsafeChannelsChanged();
        verify(remoteCallback2, times(0)).onCoexUnsafeChannelsChanged();
    }

    /**
     * Verifies that CoexManager does register as a PhoneStateListener if the default coex algorithm
     * is enabled.
     */
    @Test
    public void testPhoneStateListener_defaultAlgorithmEnabled_registersWithTelephony() {
        // config_wifiDefaultCoexAlgorithm defaults to true
        createCoexManager();

        verify(mMockTelephonyManager, times(1))
                .registerPhoneStateListener(any(Executor.class), any(PhoneStateListener.class));
    }

    /**
     * Verifies that CoexManager does not register as a PhoneStateListener if the default coex
     * algorithm is disabled.
     */
    @Test
    public void testPhoneStateListener_defaultAlgorithmDisabled_doesNotRegistersWithTelephony() {
        when(mMockResources.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled))
                .thenReturn(false);
        createCoexManager();

        verify(mMockTelephonyManager, times(0))
                .registerPhoneStateListener(any(Executor.class), any(PhoneStateListener.class));
    }
}
