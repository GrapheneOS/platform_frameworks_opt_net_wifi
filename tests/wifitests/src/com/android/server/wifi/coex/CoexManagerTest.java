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
import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;

import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_160_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_20_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_40_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_80_MHZ;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
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
import android.telephony.Annotation;
import android.telephony.PhoneStateListener;
import android.telephony.PhysicalChannelConfig;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiBaseTest;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link com.android.server.wifi.coex.CoexManager}.
 */
@SmallTest
public class CoexManagerTest extends WifiBaseTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private static final String FILEPATH_MALFORMED = "assets/coex_malformed.xml";
    private static final String FILEPATH_LTE_40_NEIGHBORING = "assets/coex_lte_40_neighboring.xml";
    private static final String FILEPATH_LTE_46_NEIGHBORING = "assets/coex_lte_46_neighboring.xml";
    private static final String FILEPATH_LTE_40_OVERRIDE = "assets/coex_lte_40_override.xml";

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private TelephonyManager mMockTelephonyManager;
    private TestLooper mTestLooper;
    private final ArgumentCaptor<CoexManager.CoexPhoneStateListener> mPhoneStateListenerCaptor =
            ArgumentCaptor.forClass(CoexManager.CoexPhoneStateListener.class);

    private CoexManager createCoexManager() {
        final CoexManager coexManager = new CoexManager(mMockContext, mMockTelephonyManager,
                new Handler(mTestLooper.getLooper()));
        return coexManager;
    }

    private PhysicalChannelConfig createMockPhysicalChannelConfig(
            @Annotation.NetworkType int rat, int arfcn, int dlBandwidthKhz, int ulBandwidthKhz) {
        PhysicalChannelConfig config = Mockito.mock(PhysicalChannelConfig.class);
        when(config.getNetworkType()).thenReturn(rat);
        when(config.getChannelNumber()).thenReturn(arfcn);
        when(config.getCellBandwidthDownlink()).thenReturn(dlBandwidthKhz);
        /* when(config.getCellBandwidthUplink()).thenReturn(ulBandwidthKhz); */
        return config;
    }

    private File createFileFromResource(String configFile) throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream(configFile);
        File file = tempFolder.newFile(configFile.split("/")[1]);

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        FileOutputStream out = new FileOutputStream(file);

        String line;

        while ((line = reader.readLine()) != null) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }

        out.flush();
        out.close();
        return file;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled))
                .thenReturn(true);
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn("");
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
     * is enabled and a coex table xml file exists and could be read.
     */
    @Test
    public void testPhoneStateListener_defaultAlgorithmEnabledXmlExists_registersWithTelephony()
            throws Exception {
        // config_wifiDefaultCoexAlgorithm defaults to true
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_40_NEIGHBORING).getCanonicalPath());
        createCoexManager();

        verify(mMockTelephonyManager, times(1))
                .registerPhoneStateListener(any(Executor.class), any(PhoneStateListener.class));
    }

    /**
     * Verifies that CoexManager does not register as a PhoneStateListener if the default coex
     * algorithm is disabled.
     */
    @Test
    public void testPhoneStateListener_defaultAlgorithmDisabled_doesNotRegisterWithTelephony()
            throws Exception {
        when(mMockResources.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled))
                .thenReturn(false);
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_40_NEIGHBORING).getCanonicalPath());
        createCoexManager();

        verify(mMockTelephonyManager, times(0))
                .registerPhoneStateListener(any(Executor.class), any(PhoneStateListener.class));
    }

    /**
     * Verifies that readTableFromXml returns false if the coex table XML is missing or malformed.
     */
    @Test
    public void testPhoneStateListener_missingOrMalformedXml_doesNotRegisterWithTelephony()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_MALFORMED).getCanonicalPath());
        CoexManager coexManager = createCoexManager();

        verify(mMockTelephonyManager, times(0))
                .registerPhoneStateListener(any(Executor.class), any(PhoneStateListener.class));
    }

    /**
     * Verifies that CoexManager returns the correct 2.4Ghz CoexUnsafeChannels for a cell channel
     * in the neighboring LTE band 40.
     */
    @Test
    public void testGetCoexUnsafeChannels_neighboringLte40_returns2gNeighboringChannels()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_40_NEIGHBORING).getCanonicalPath());
        CoexManager coexManager = createCoexManager();
        verify(mMockTelephonyManager).registerPhoneStateListener(any(Executor.class),
                mPhoneStateListenerCaptor.capture());

        mPhoneStateListenerCaptor.getValue().onPhysicalChannelConfigChanged(Arrays.asList(
                createMockPhysicalChannelConfig(NETWORK_TYPE_LTE, 39649, 10_000, 0)
        ));

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactly(
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 1, -50),
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 2, -50),
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 3, -50),
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 4, -50),
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 5, -50)
        );
    }

    /**
     * Verifies that CoexManager returns the correct 5Ghz CoexUnsafeChannels for a cell channel
     * in the neighboring LTE band 46.
     */
    @Test
    public void testGetCoexUnsafeChannels_neighboringLte46_returns5gNeighboringChannels()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_46_NEIGHBORING).getCanonicalPath());
        CoexManager coexManager = createCoexManager();
        verify(mMockTelephonyManager).registerPhoneStateListener(
                any(Executor.class), mPhoneStateListenerCaptor.capture());

        mPhoneStateListenerCaptor.getValue().onPhysicalChannelConfigChanged(Arrays.asList(
                createMockPhysicalChannelConfig(NETWORK_TYPE_LTE, 46790, 10_000, 0)
        ));

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactly(
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 32, -50),
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 34, -50),
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36, -50),
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 38, -50),
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 42, -50),
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 50, -50)
        );
    }

    /**
     * Verifies that CoexManager returns the full list of 2.4GHz CoexUnsafeChannels excluding the
     * default channel if the entire 2.4GHz band is unsafe.
     */
    @Test
    public void testGetCoexUnsafeChannels_entire2gBandUnsafe_excludesDefault2gChannel()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_40_NEIGHBORING).getCanonicalPath());
        CoexManager coexManager = createCoexManager();
        verify(mMockTelephonyManager).registerPhoneStateListener(
                any(Executor.class), mPhoneStateListenerCaptor.capture());

        mPhoneStateListenerCaptor.getValue().onPhysicalChannelConfigChanged(Arrays.asList(
                createMockPhysicalChannelConfig(NETWORK_TYPE_LTE, 39649, 2000_000, 0)
        ));

        assertThat(coexManager.getCoexUnsafeChannels()).hasSize(13);
        assertThat(coexManager.getCoexUnsafeChannels()).doesNotContain(
                new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6, -50));
    }

    /**
     * Verifies that CoexManager returns the full list of 5GHz CoexUnsafeChannels excluding the
     * default channel if the entire 5GHz band is unsafe.
     */
    @Test
    public void testGetCoexUnsafeChannels_entire5gBandUnsafe_excludesDefault5gChannel()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_46_NEIGHBORING).getCanonicalPath());
        CoexManager coexManager = createCoexManager();
        verify(mMockTelephonyManager).registerPhoneStateListener(
                any(Executor.class), mPhoneStateListenerCaptor.capture());

        mPhoneStateListenerCaptor.getValue().onPhysicalChannelConfigChanged(Arrays.asList(
                createMockPhysicalChannelConfig(NETWORK_TYPE_LTE, 46790, 2000_000, 0)
        ));

        assertThat(coexManager.getCoexUnsafeChannels()).hasSize(CHANNEL_SET_5_GHZ_20_MHZ.size()
                + CHANNEL_SET_5_GHZ_40_MHZ.size() + CHANNEL_SET_5_GHZ_80_MHZ.size()
                + CHANNEL_SET_5_GHZ_160_MHZ.size() - 1);
        assertThat(coexManager.getCoexUnsafeChannels()).doesNotContain(
                new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 36, -50));
    }

    /**
     * Verifies that CoexManager returns the list of channels specified in the override list of a
     * corresponding cell band.
     */
    @Test
    public void testGetCoexUnsafeChannels_overrideExists_overrideChannelsAdded()
            throws Exception {
        when(mMockResources.getString(R.string.config_wifiCoexTableFilepath))
                .thenReturn(createFileFromResource(FILEPATH_LTE_40_OVERRIDE).getCanonicalPath());
        CoexManager coexManager = createCoexManager();
        Set<CoexUnsafeChannel> unsafeChannels = new HashSet<>();
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 6));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, 11));
        for (int channel : CHANNEL_SET_5_GHZ_20_MHZ) {
            unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, channel));
        }
        for (int channel : CHANNEL_SET_5_GHZ_40_MHZ) {
            unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, channel));
        }
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 50));
        unsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 114));
        verify(mMockTelephonyManager).registerPhoneStateListener(
                any(Executor.class), mPhoneStateListenerCaptor.capture());

        mPhoneStateListenerCaptor.getValue().onPhysicalChannelConfigChanged(Arrays.asList(
                createMockPhysicalChannelConfig(NETWORK_TYPE_LTE, 39649, 10_000, 0)
        ));

        assertThat(coexManager.getCoexUnsafeChannels()).containsExactlyElementsIn(unsafeChannels);
    }
}
