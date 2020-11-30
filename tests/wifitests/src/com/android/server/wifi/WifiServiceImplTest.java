/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_METERED;
import static android.net.wifi.WifiManager.DEVICE_MOBILITY_STATE_STATIONARY;
import static android.net.wifi.WifiManager.EASY_CONNECT_CRYPTOGRAPHY_CURVE_PRIME256V1;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.REQUEST_REGISTERED;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_GENERAL;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_NO_CHANNEL;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;
import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_VERBOSE_LOGGING_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.AdditionalAnswers.returnsSecondArg;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.test.MockAnswerUtil;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.net.MacAddress;
import android.net.NetworkStack;
import android.net.Uri;
import android.net.wifi.IActionListener;
import android.net.wifi.IDppCallback;
import android.net.wifi.ILocalOnlyHotspotCallback;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.IOnWifiActivityEnergyInfoListener;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.IScanResultsCallback;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.ISuggestionConnectionStatusListener;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.SoftApCallback;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.test.TestLooper;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.internal.os.PowerProfile;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.WifiServiceImpl.LocalOnlyRequestorCallback;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointProvisioningTestUtil;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.util.ActionListenerWrapper;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;
import com.android.wifi.resources.R;

import com.google.common.base.Strings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link WifiServiceImpl}.
 *
 * Note: this is intended to build up over time and will not immediately cover the entire file.
 */
@SmallTest
public class WifiServiceImplTest extends WifiBaseTest {

    private static final String TAG = "WifiServiceImplTest";
    private static final String SCAN_PACKAGE_NAME = "scanPackage";
    private static final int DEFAULT_VERBOSE_LOGGING = 0;
    private static final String ANDROID_SYSTEM_PACKAGE = "android";
    private static final String TEST_PACKAGE_NAME = "TestPackage";
    private static final String TEST_FEATURE_ID = "TestFeature";
    private static final String SYSUI_PACKAGE_NAME = "com.android.systemui";
    private static final int TEST_PID = 6789;
    private static final int TEST_PID2 = 9876;
    private static final int TEST_UID = 1200000;
    private static final int OTHER_TEST_UID = 1300000;
    private static final int TEST_USER_HANDLE = 13;
    private static final int TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER = 17;
    private static final int TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER = 234;
    private static final int TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER = 2;
    private static final int TEST_WIFI_CONNECTED_NETWORK_SCORER_IDENTIFIER = 1;
    private static final String WIFI_IFACE_NAME = "wlan0";
    private static final String WIFI_IFACE_NAME2 = "wlan1";
    private static final String TEST_COUNTRY_CODE = "US";
    private static final String TEST_FACTORY_MAC = "10:22:34:56:78:92";
    private static final MacAddress TEST_FACTORY_MAC_ADDR = MacAddress.fromString(TEST_FACTORY_MAC);
    private static final String TEST_FQDN = "testfqdn";
    private static final String TEST_FRIENDLY_NAME = "testfriendlyname";
    private static final List<WifiConfiguration> TEST_WIFI_CONFIGURATION_LIST = Arrays.asList(
            WifiConfigurationTestUtil.generateWifiConfig(
                    0, 1000000, "\"red\"", true, true, null, null),
            WifiConfigurationTestUtil.generateWifiConfig(
                    1, 1000001, "\"green\"", true, false, "example.com", "Green"),
            WifiConfigurationTestUtil.generateWifiConfig(
                    2, 1200000, "\"blue\"", false, true, null, null),
            WifiConfigurationTestUtil.generateWifiConfig(
                    3, 1100000, "\"cyan\"", true, true, null, null),
            WifiConfigurationTestUtil.generateWifiConfig(
                    4, 1100001, "\"yellow\"", true, true, "example.org", "Yellow"),
            WifiConfigurationTestUtil.generateWifiConfig(
                    5, 1100002, "\"magenta\"", false, false, null, null));
    private static final int TEST_AP_FREQUENCY = 2412;
    private static final int TEST_AP_BANDWIDTH = SoftApInfo.CHANNEL_WIDTH_20MHZ;
    private static final int NETWORK_CALLBACK_ID = 1100;
    private static final String TEST_CAP = "[RSN-PSK-CCMP]";
    private static final String TEST_SSID = "Sid's Place";
    private static final String TEST_SSID_WITH_QUOTES = "\"" + TEST_SSID + "\"";
    private static final String TEST_BSSID = "01:02:03:04:05:06";
    private static final String TEST_PACKAGE = "package";
    private static final int TEST_NETWORK_ID = 567;
    private static final WorkSource TEST_SETTINGS_WORKSOURCE = new WorkSource();

    private SoftApInfo mTestSoftApInfo;
    private WifiServiceImpl mWifiServiceImpl;
    private TestLooper mLooper;
    private WifiThreadRunner mWifiThreadRunner;
    private PowerManager mPowerManager;
    private PhoneStateListener mPhoneStateListener;
    private int mPid;
    private int mPid2 = Process.myPid();
    private OsuProvider mOsuProvider;
    private SoftApCallback mStateMachineSoftApCallback;
    private SoftApCallback mLohsApCallback;
    private String mLohsInterfaceName;
    private ApplicationInfo mApplicationInfo;
    private List<ClientModeManager> mClientModeManagers;
    private static final String DPP_URI = "DPP:some_dpp_uri";
    private static final String DPP_PRODUCT_INFO = "DPP:some_dpp_uri_info";

    private final ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    final ArgumentCaptor<SoftApModeConfiguration> mSoftApModeConfigCaptor =
            ArgumentCaptor.forClass(SoftApModeConfiguration.class);

    @Mock Context mContext;
    @Mock Context mContextAsUser;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiCountryCode mWifiCountryCode;
    @Mock Clock mClock;
    @Mock WifiTrafficPoller mWifiTrafficPoller;
    @Mock ClientModeManager mClientModeManager;
    @Mock ActiveModeWarden mActiveModeWarden;
    @Mock HandlerThread mHandlerThread;
    @Mock Resources mResources;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock WifiLockManager mLockManager;
    @Mock WifiMulticastLockManager mWifiMulticastLockManager;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock WifiBackupRestore mWifiBackupRestore;
    @Mock SoftApBackupRestore mSoftApBackupRestore;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiPermissionsWrapper mWifiPermissionsWrapper;
    @Mock WifiSettingsStore mSettingsStore;
    @Mock ContentResolver mContentResolver;
    @Mock PackageManager mPackageManager;
    @Mock UserManager mUserManager;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock WifiConfiguration mApConfig;
    @Mock ActivityManager mActivityManager;
    @Mock AppOpsManager mAppOpsManager;
    @Mock IBinder mAppBinder;
    @Mock IBinder mAnotherAppBinder;
    @Mock LocalOnlyHotspotRequestInfo mRequestInfo;
    @Mock LocalOnlyHotspotRequestInfo mRequestInfo2;
    @Mock IProvisioningCallback mProvisioningCallback;
    @Mock ISoftApCallback mClientSoftApCallback;
    @Mock ISoftApCallback mAnotherSoftApCallback;
    @Mock PowerProfile mPowerProfile;
    @Mock WifiTrafficPoller mWifiTrafficPolller;
    @Mock ScanRequestProxy mScanRequestProxy;
    @Mock WakeupController mWakeupController;
    @Mock ITrafficStateCallback mTrafficStateCallback;
    @Mock INetworkRequestMatchCallback mNetworkRequestMatchCallback;
    @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    @Mock TelephonyManager mTelephonyManager;
    @Mock IOnWifiUsabilityStatsListener mOnWifiUsabilityStatsListener;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiScoreCard mWifiScoreCard;
    @Mock WifiHealthMonitor mWifiHealthMonitor;
    @Mock PasspointManager mPasspointManager;
    @Mock IDppCallback mDppCallback;
    @Mock ILocalOnlyHotspotCallback mLohsCallback;
    @Mock IScanResultsCallback mScanResultsCallback;
    @Mock ISuggestionConnectionStatusListener mSuggestionConnectionStatusListener;
    @Mock IOnWifiActivityEnergyInfoListener mOnWifiActivityEnergyInfoListener;
    @Mock IWifiConnectedNetworkScorer mWifiConnectedNetworkScorer;
    @Mock WifiSettingsConfigStore mWifiSettingsConfigStore;
    @Mock WifiScanAlwaysAvailableSettingsCompatibility mScanAlwaysAvailableSettingsCompatibility;
    @Mock PackageInfo mPackageInfo;
    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock WifiDataStall mWifiDataStall;
    @Mock WifiNative mWifiNative;
    @Mock ConnectHelper mConnectHelper;
    @Mock IActionListener mActionListener;
    @Mock WifiNetworkFactory mWifiNetworkFactory;
    @Mock UntrustedWifiNetworkFactory mUntrustedWifiNetworkFactory;
    @Mock OemPaidWifiNetworkFactory mOemPaidWifiNetworkFactory;
    @Mock OemPrivateWifiNetworkFactory mOemPrivateWifiNetworkFactory;
    @Mock WifiDiagnostics mWifiDiagnostics;
    @Mock WifiP2pConnection mWifiP2pConnection;
    @Mock SimRequiredNotifier mSimRequiredNotifier;
    @Mock WifiGlobals mWifiGlobals;
    @Mock AdaptiveConnectivityEnabledSettingObserver mAdaptiveConnectivityEnabledSettingObserver;

    @Captor ArgumentCaptor<Intent> mIntentCaptor;

    WifiConfiguration mWifiConfig;

    WifiLog mLog;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLog = spy(new LogcatLog(TAG));
        mLooper = new TestLooper();
        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        when(mResources.getInteger(
                eq(R.integer.config_wifiHardwareSoftapMaxClientCount)))
                .thenReturn(10);
        WifiInjector.sWifiInjector = mWifiInjector;
        when(mRequestInfo.getPid()).thenReturn(mPid);
        when(mRequestInfo2.getPid()).thenReturn(mPid2);
        when(mWifiInjector.getUserManager()).thenReturn(mUserManager);
        when(mWifiInjector.getWifiCountryCode()).thenReturn(mWifiCountryCode);
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getWifiNetworkFactory()).thenReturn(mWifiNetworkFactory);
        when(mWifiInjector.getUntrustedWifiNetworkFactory())
                .thenReturn(mUntrustedWifiNetworkFactory);
        when(mWifiInjector.getOemPaidWifiNetworkFactory())
                .thenReturn(mOemPaidWifiNetworkFactory);
        when(mWifiInjector.getOemPrivateWifiNetworkFactory())
                .thenReturn(mOemPrivateWifiNetworkFactory);
        when(mWifiInjector.getWifiDiagnostics()).thenReturn(mWifiDiagnostics);
        when(mWifiInjector.getActiveModeWarden()).thenReturn(mActiveModeWarden);
        when(mWifiInjector.getWifiHandlerThread()).thenReturn(mHandlerThread);
        when(mHandlerThread.getThreadHandler()).thenReturn(new Handler(mLooper.getLooper()));
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        when(mWifiInjector.getWifiApConfigStore()).thenReturn(mWifiApConfigStore);
        doNothing().when(mFrameworkFacade).registerContentObserver(eq(mContext), any(),
                anyBoolean(), any());
        when(mFrameworkFacade.getSettingsWorkSource(any())).thenReturn(TEST_SETTINGS_WORKSOURCE);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        IPowerManager powerManagerService = mock(IPowerManager.class);
        IThermalService thermalService = mock(IThermalService.class);
        mPowerManager =
                new PowerManager(mContext, powerManagerService, thermalService, new Handler());
        when(mContext.getSystemServiceName(PowerManager.class)).thenReturn(Context.POWER_SERVICE);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mContext.createContextAsUser(eq(UserHandle.CURRENT), anyInt()))
                .thenReturn(mContextAsUser);
        when(mWifiInjector.getFrameworkFacade()).thenReturn(mFrameworkFacade);
        when(mWifiInjector.getWifiLockManager()).thenReturn(mLockManager);
        when(mWifiInjector.getWifiMulticastLockManager()).thenReturn(mWifiMulticastLockManager);
        when(mWifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(mWifiInjector.getWifiBackupRestore()).thenReturn(mWifiBackupRestore);
        when(mWifiInjector.getSoftApBackupRestore()).thenReturn(mSoftApBackupRestore);
        when(mWifiInjector.makeLog(anyString())).thenReturn(mLog);
        when(mWifiInjector.getWifiTrafficPoller()).thenReturn(mWifiTrafficPoller);
        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiInjector.getWifiPermissionsWrapper()).thenReturn(mWifiPermissionsWrapper);
        when(mWifiInjector.getWifiSettingsStore()).thenReturn(mSettingsStore);
        when(mWifiInjector.getClock()).thenReturn(mClock);
        when(mWifiInjector.getScanRequestProxy()).thenReturn(mScanRequestProxy);
        when(mWifiInjector.getWakeupController()).thenReturn(mWakeupController);
        when(mWifiInjector.getWifiNetworkSuggestionsManager())
                .thenReturn(mWifiNetworkSuggestionsManager);
        when(mWifiInjector.makeTelephonyManager()).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mWifiInjector.getWifiConfigManager()).thenReturn(mWifiConfigManager);
        when(mWifiInjector.getPasspointManager()).thenReturn(mPasspointManager);
        when(mActiveModeWarden.getPrimaryClientModeManager()).thenReturn(mClientModeManager);
        when(mClientModeManager.getInterfaceName()).thenReturn(WIFI_IFACE_NAME);
        when(mWifiInjector.getWifiScoreCard()).thenReturn(mWifiScoreCard);
        when(mWifiInjector.getWifiHealthMonitor()).thenReturn(mWifiHealthMonitor);
        when(mWifiInjector.getWifiNetworkScoreCache())
                .thenReturn(mock(WifiNetworkScoreCache.class));

        mWifiThreadRunner = new WifiThreadRunner(new Handler(mLooper.getLooper()));
        mWifiThreadRunner.setTimeoutsAreErrors(true);
        when(mWifiInjector.getWifiThreadRunner()).thenReturn(mWifiThreadRunner);

        when(mWifiInjector.getSettingsConfigStore()).thenReturn(mWifiSettingsConfigStore);
        when(mWifiInjector.getWifiScanAlwaysAvailableSettingsCompatibility())
                .thenReturn(mScanAlwaysAvailableSettingsCompatibility);
        when(mWifiInjector.getWifiConnectivityManager()).thenReturn(mWifiConnectivityManager);
        when(mWifiInjector.getWifiDataStall()).thenReturn(mWifiDataStall);
        when(mWifiInjector.getWifiNative()).thenReturn(mWifiNative);
        when(mWifiInjector.getConnectHelper()).thenReturn(mConnectHelper);
        when(mWifiInjector.getWifiP2pConnection()).thenReturn(mWifiP2pConnection);
        when(mWifiInjector.getSimRequiredNotifier()).thenReturn(mSimRequiredNotifier);
        when(mWifiInjector.getWifiGlobals()).thenReturn(mWifiGlobals);
        when(mWifiInjector.getAdaptiveConnectivityEnabledSettingObserver())
                .thenReturn(mAdaptiveConnectivityEnabledSettingObserver);
        when(mClientModeManager.syncStartSubscriptionProvisioning(anyInt(),
                any(OsuProvider.class), any(IProvisioningCallback.class))).thenReturn(true);
        // Create an OSU provider that can be provisioned via an open OSU AP
        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mContext.getAttributionTag()).thenReturn(TEST_FEATURE_ID);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_STACK),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_MANAGED_PROVISIONING),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mScanRequestProxy.startScan(anyInt(), anyString())).thenReturn(true);
        when(mLohsCallback.asBinder()).thenReturn(mock(IBinder.class));
        when(mWifiSettingsConfigStore.get(eq(WIFI_VERBOSE_LOGGING_ENABLED))).thenReturn(true);
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(false);

        mClientModeManagers = Arrays.asList(mClientModeManager, mock(ClientModeManager.class));
        when(mActiveModeWarden.getClientModeManagers()).thenReturn(mClientModeManagers);

        mWifiServiceImpl = makeWifiServiceImpl();
        mDppCallback = new IDppCallback() {
            @Override
            public void onSuccessConfigReceived(int newNetworkId) throws RemoteException {

            }

            @Override
            public void onSuccess(int status) throws RemoteException {

            }

            @Override
            public void onFailure(int status, String ssid, String channelList, int[] bandList)
                    throws RemoteException {

            }

            @Override
            public void onProgress(int status) throws RemoteException {

            }

            @Override
            public void onBootstrapUriGenerated(String uri) throws RemoteException {

            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };

        // permission not granted by default
        doThrow(SecurityException.class).when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.NETWORK_SETUP_WIZARD), any());
        mTestSoftApInfo = new SoftApInfo();
        mTestSoftApInfo.setFrequency(TEST_AP_FREQUENCY);
        mTestSoftApInfo.setBandwidth(TEST_AP_BANDWIDTH);
        when(mWifiNative.getChannelsForBand(anyInt())).thenReturn(new int[0]);

        mWifiConfig = new WifiConfiguration();
        mWifiConfig.SSID = TEST_SSID;
        mWifiConfig.networkId = TEST_NETWORK_ID;

        mWifiThreadRunner.prepareForAutoDispatch();
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private WifiServiceImpl makeWifiServiceImpl() {
        WifiServiceImpl wifiServiceImpl =
                new WifiServiceImpl(mContext, mWifiInjector);
        ArgumentCaptor<SoftApCallback> softApCallbackCaptor =
                ArgumentCaptor.forClass(SoftApCallback.class);
        verify(mActiveModeWarden, atLeastOnce()).registerSoftApCallback(
                softApCallbackCaptor.capture());
        mStateMachineSoftApCallback = softApCallbackCaptor.getValue();
        ArgumentCaptor<SoftApCallback> lohsCallbackCaptor =
                ArgumentCaptor.forClass(SoftApCallback.class);
        mLohsInterfaceName = WIFI_IFACE_NAME;
        verify(mActiveModeWarden, atLeastOnce()).registerLohsCallback(
                lohsCallbackCaptor.capture());
        mLohsApCallback = lohsCallbackCaptor.getValue();
        mLooper.dispatchAll();
        return wifiServiceImpl;
    }

    private WifiServiceImpl makeWifiServiceImplWithMockRunnerWhichTimesOut() {
        WifiThreadRunner mockRunner = mock(WifiThreadRunner.class);
        when(mockRunner.call(any(), any())).then(returnsSecondArg());
        when(mockRunner.call(any(), any(int.class))).then(returnsSecondArg());
        when(mockRunner.call(any(), any(boolean.class))).then(returnsSecondArg());
        when(mockRunner.post(any())).thenReturn(false);

        when(mWifiInjector.getWifiThreadRunner()).thenReturn(mockRunner);
        return makeWifiServiceImpl();
    }

    /**
     * Test that REMOVE_NETWORK returns failure to public API when WifiConfigManager returns
     * failure.
     */
    @Test
    public void testRemoveNetworkFailureAppBelowQSdk() {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME,
                        TEST_FEATURE_ID, null);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mWifiConfigManager.removeNetwork(anyInt(), anyInt(), anyString())).thenReturn(false);

        mLooper.startAutoDispatch();
        boolean succeeded = mWifiServiceImpl.removeNetwork(0, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertFalse(succeeded);
    }

    /**
     * Ensure WifiMetrics.dump() is the only dump called when 'dumpsys wifi WifiMetricsProto' is
     * called. This is required to support simple metrics collection via dumpsys
     */
    @Test
    public void testWifiMetricsDump() {
        mLooper.startAutoDispatch();
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()),
                new String[]{mWifiMetrics.PROTO_DUMP_ARG});
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWifiMetrics).setEnhancedMacRandomizationForceEnabled(anyBoolean());
        verify(mWifiMetrics).setIsScanningAlwaysEnabled(anyBoolean());
        verify(mWifiMetrics).setVerboseLoggingEnabled(anyBoolean());
        verify(mWifiMetrics)
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
        verify(mClientModeManager, never())
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
    }

    /**
     * Ensure WifiServiceImpl.dump() doesn't throw an NPE when executed with null args
     */
    @Test
    public void testDumpNullArgs() {
        mLooper.startAutoDispatch();
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), null);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiDiagnostics).captureBugReportData(
                WifiDiagnostics.REPORT_REASON_USER_ACTION);
        verify(mWifiDiagnostics).dump(any(), any(), any());
    }

    /**
     * Verify that metrics is incremented correctly for Privileged Apps.
     */
    @Test
    public void testSetWifiEnabledMetricsPrivilegedApp() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mSettingsStore.handleWifiToggled(anyBoolean())).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);

        InOrder inorder = inOrder(mWifiMetrics);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        inorder.verify(mWifiMetrics).logUserActionEvent(UserActionEvent.EVENT_TOGGLE_WIFI_ON);
        inorder.verify(mWifiMetrics).incrementNumWifiToggles(eq(true), eq(true));
        inorder.verify(mWifiMetrics).logUserActionEvent(eq(UserActionEvent.EVENT_TOGGLE_WIFI_OFF),
                anyInt());
        inorder.verify(mWifiMetrics).incrementNumWifiToggles(eq(true), eq(false));
    }

    /**
     * Verify that metrics is incremented correctly for normal Apps targeting pre-Q.
     */
    @Test
    public void testSetWifiEnabledMetricsNormalAppBelowQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mSettingsStore.handleWifiToggled(anyBoolean())).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);

        InOrder inorder = inOrder(mWifiMetrics);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        inorder.verify(mWifiMetrics).incrementNumWifiToggles(eq(false), eq(true));
        inorder.verify(mWifiMetrics).incrementNumWifiToggles(eq(false), eq(false));
    }

    /**
     * Verify that metrics is not incremented by apps targeting Q SDK.
     */
    @Test
    public void testSetWifiEnabledMetricsNormalAppTargetingQSdkNoIncrement() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);

        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mWifiMetrics, never()).incrementNumWifiToggles(anyBoolean(), anyBoolean());
    }

    /**
     * Verify that wifi can be enabled by a caller with NETWORK_SETTINGS permission.
     */
    @Test
    public void testSetWifiEnabledSuccessWithNetworkSettingsPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be enabled by a caller with NETWORK_MANAGED_PROVISIONING permission.
     */
    @Test
    public void testSetWifiEnabledSuccessWithNetworkManagedProvisioningPermission()
            throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_MANAGED_PROVISIONING),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be enabled by the DO apps targeting Q SDK.
     */
    @Test
    public void testSetWifiEnabledSuccessForDOAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isDeviceOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be enabled by the system apps targeting Q SDK.
     */
    @Test
    public void testSetWifiEnabledSuccessForSystemAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be enabled by the apps targeting pre-Q SDK.
     */
    @Test
    public void testSetWifiEnabledSuccessForAppsTargetingBelowQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi cannot be enabled by the apps targeting Q SDK.
     */
    @Test
    public void testSetWifiEnabledFailureForAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);

        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));

        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify a SecurityException is thrown if OPSTR_CHANGE_WIFI_STATE is disabled for the app.
     */
    @Test
    public void testSetWifiEnableAppOpsRejected() throws Exception {
        doThrow(new SecurityException()).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        try {
            mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true);
            fail();
        } catch (SecurityException e) {

        }
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify a SecurityException is thrown if OP_CHANGE_WIFI_STATE is set to MODE_IGNORED
     * for the app.
     */
    @Test // No exception expected, but the operation should not be done
    public void testSetWifiEnableAppOpsIgnored() throws Exception {
        doReturn(AppOpsManager.MODE_IGNORED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);

        mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true);
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify that a call from an app with the NETWORK_SETTINGS permission can enable wifi if we
     * are in airplane mode.
     */
    @Test
    public void testSetWifiEnabledFromNetworkSettingsHolderWhenInAirplaneMode() throws Exception {
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(true);
        when(mContext.checkPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertTrue(mWifiServiceImpl.setWifiEnabled(SYSUI_PACKAGE_NAME, true));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), SYSUI_PACKAGE_NAME)));
    }

    /**
     * Verify that a caller without the NETWORK_SETTINGS permission can't enable wifi
     * if we are in airplane mode.
     */
    @Test
    public void testSetWifiEnabledFromAppFailsWhenInAirplaneMode() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(true);
        when(mContext.checkPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Helper to verify registering for state changes.
     */
    private void verifyApRegistration() {
        assertNotNull(mLohsApCallback);
    }

    /**
     * Helper to emulate local-only hotspot state changes.
     *
     * Must call verifyApRegistration first.
     */
    private void changeLohsState(int apState, int previousState, int error) {
        // TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
        //        apState, previousState, error, WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLohsApCallback.onStateChanged(apState, error);
    }

    /**
     * Verify that a call from an app with the NETWORK_SETTINGS permission can enable wifi if we
     * are in softap mode.
     */
    @Test
    public void testSetWifiEnabledFromNetworkSettingsHolderWhenApEnabled() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);

        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mContext.checkPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(SYSUI_PACKAGE_NAME, true));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), SYSUI_PACKAGE_NAME)));
    }

    /**
     * Verify that a call from an app cannot enable wifi if we are in softap mode.
     */
    @Test
    public void testSetWifiEnabledFromAppFailsWhenApEnabled() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);

        when(mContext.checkPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mSettingsStore, never()).handleWifiToggled(anyBoolean());
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }


    /**
     * Verify that the CMD_TOGGLE_WIFI message won't be sent if wifi is already on.
     */
    @Test
    public void testSetWifiEnabledNoToggle() throws Exception {
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(false);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the CHANGE_WIFI_STATE
     * permission to toggle wifi.
     */
    @Test
    public void testSetWifiEnableWithoutChangeWifiStatePermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        try {
            mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true);
            fail();
        } catch (SecurityException e) {
        }
    }

    /**
     * Verify that wifi can be disabled by a caller with NETWORK_SETTINGS permission.
     */
    @Test
    public void testSetWifiDisabledSuccessWithNetworkSettingsPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be disabled by a caller with NETWORK_MANAGED_PROVISIONING permission.
     */
    @Test
    public void testSetWifiDisabledSuccessWithNetworkManagedProvisioningPermission()
            throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_MANAGED_PROVISIONING),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be disabled by the PO apps targeting Q SDK.
     */
    @Test
    public void testSetWifiDisabledSuccessForPOAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isProfileOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi can be disabled by the system apps targeting Q SDK.
     */
    @Test
    public void testSetWifiDisabledSuccessForSystemAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }


    /**
     * Verify that wifi can be disabled by the apps targeting pre-Q SDK.
     */
    @Test
    public void testSetWifiDisabledSuccessForAppsTargetingBelowQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));

        verify(mActiveModeWarden).wifiToggled(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify that wifi cannot be disabled by the apps targeting Q SDK.
     */
    @Test
    public void testSetWifiDisabledFailureForAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(false);

        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));

        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify that CMD_TOGGLE_WIFI message won't be sent if wifi is already off.
     */
    @Test
    public void testSetWifiDisabledNoToggle() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the CHANGE_WIFI_STATE
     * permission to toggle wifi.
     */
    @Test
    public void testSetWifiDisabledWithoutChangeWifiStatePermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false);
            fail();
        } catch (SecurityException e) { }
    }

    /**
     * Ensure unpermitted callers cannot write the SoftApConfiguration.
     */
    @Test
    public void testSetWifiApConfigurationNotSavedWithoutPermission() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        WifiConfiguration apConfig = new WifiConfiguration();
        try {
            mWifiServiceImpl.setWifiApConfiguration(apConfig, TEST_PACKAGE_NAME);
            fail("Expected SecurityException");
        } catch (SecurityException e) { }
    }

    /**
     * Ensure softap config is written when the caller has the correct permission.
     */
    @Test
    public void testSetWifiApConfigurationSuccess() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        WifiConfiguration wifiApConfig = createValidWifiApConfiguration();

        assertTrue(mWifiServiceImpl.setWifiApConfiguration(wifiApConfig, TEST_PACKAGE_NAME));
        mLooper.dispatchAll();
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiApConfigStore).setApConfiguration(eq(
                ApConfigUtil.fromWifiConfiguration(wifiApConfig)));
    }

    /**
     * Ensure that a null config does not overwrite the saved ap config.
     */
    @Test
    public void testSetWifiApConfigurationNullConfigNotSaved() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setWifiApConfiguration(null, TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(isNull(SoftApConfiguration.class));
    }

    /**
     * Ensure that an invalid config does not overwrite the saved ap config.
     */
    @Test
    public void testSetWifiApConfigurationWithInvalidConfigNotSaved() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setWifiApConfiguration(new WifiConfiguration(),
                                                            TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(any());
    }

    /**
     * Ensure unpermitted callers cannot write the SoftApConfiguration.
     */
    @Test
    public void testSetSoftApConfigurationNotSavedWithoutPermission() throws Exception {
        SoftApConfiguration apConfig = createValidSoftApConfiguration();
        try {
            mWifiServiceImpl.setSoftApConfiguration(apConfig, TEST_PACKAGE_NAME);
            fail("Expected SecurityException");
        } catch (SecurityException e) { }
    }

    /**
     * Ensure softap config is written when the caller has the correct permission.
     */
    @Test
    public void testSetSoftApConfigurationSuccessWithSettingPermission() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        SoftApConfiguration apConfig = createValidSoftApConfiguration();

        assertTrue(mWifiServiceImpl.setSoftApConfiguration(apConfig, TEST_PACKAGE_NAME));
        mLooper.dispatchAll();
        verify(mWifiApConfigStore).setApConfiguration(eq(apConfig));
        verify(mActiveModeWarden).updateSoftApConfiguration(apConfig);
        verify(mWifiPermissionsUtil).checkNetworkSettingsPermission(anyInt());
    }

    /**
     * Ensure softap config is written when the caller has the correct permission.
     */
    @Test
    public void testSetSoftApConfigurationSuccessWithOverridePermission() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        SoftApConfiguration apConfig = createValidSoftApConfiguration();

        assertTrue(mWifiServiceImpl.setSoftApConfiguration(apConfig, TEST_PACKAGE_NAME));
        mLooper.dispatchAll();
        verify(mWifiApConfigStore).setApConfiguration(eq(apConfig));
        verify(mActiveModeWarden).updateSoftApConfiguration(apConfig);
        verify(mWifiPermissionsUtil).checkConfigOverridePermission(anyInt());
    }

    /**
     * Ensure that a null config does not overwrite the saved ap config.
     */
    @Test
    public void testSetSoftApConfigurationNullConfigNotSaved() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setSoftApConfiguration(null, TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(isNull(SoftApConfiguration.class));
        verify(mActiveModeWarden, never()).updateSoftApConfiguration(any());
        verify(mWifiPermissionsUtil).checkConfigOverridePermission(anyInt());
    }

    /**
     * Ensure that an invalid config does not overwrite the saved ap config.
     */
    @Test
    public void testSetSoftApConfigurationWithInvalidConfigNotSaved() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setSoftApConfiguration(
                new SoftApConfiguration.Builder().build(), TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(any());
        verify(mWifiPermissionsUtil).checkConfigOverridePermission(anyInt());
    }

    /**
     * Ensure unpermitted callers are not able to retrieve the softap config.
     */
    @Test
    public void testGetSoftApConfigurationNotReturnedWithoutPermission() throws Exception {
        try {
            mWifiServiceImpl.getSoftApConfiguration();
            fail("Expected a SecurityException");
        } catch (SecurityException e) {
        }
    }

    /**
     * Ensure permitted callers are able to retrieve the softap config.
     */
    @Test
    public void testGetSoftApConfigurationSuccess() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        SoftApConfiguration apConfig = createValidSoftApConfiguration();
        when(mWifiApConfigStore.getApConfiguration()).thenReturn(apConfig);

        mLooper.startAutoDispatch();
        assertThat(apConfig).isEqualTo(mWifiServiceImpl.getSoftApConfiguration());

        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Ensure unpermitted callers are not able to retrieve the softap config.
     */
    @Test
    public void testGetWifiApConfigurationNotReturnedWithoutPermission() throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        try {
            mWifiServiceImpl.getWifiApConfiguration();
            fail("Expected a SecurityException");
        } catch (SecurityException e) {
        }
    }

    /**
     * Ensure permitted callers are able to retrieve the softap config.
     */
    @Test
    public void testGetWifiApConfigurationSuccess() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        SoftApConfiguration apConfig = new SoftApConfiguration.Builder().build();
        when(mWifiApConfigStore.getApConfiguration()).thenReturn(apConfig);

        mLooper.startAutoDispatch();
        WifiConfigurationTestUtil.assertConfigurationEqualForSoftAp(
                apConfig.toWifiConfiguration(),
                mWifiServiceImpl.getWifiApConfiguration());

        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Ensure we return the proper variable for the softap state after getting an AP state change
     * broadcast.
     */
    @Test
    public void testGetWifiApEnabled() throws Exception {
        // ap should be disabled when wifi hasn't been started
        assertEquals(WifiManager.WIFI_AP_STATE_DISABLED, mWifiServiceImpl.getWifiApEnabledState());

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        // ap should be disabled initially
        assertEquals(WifiManager.WIFI_AP_STATE_DISABLED, mWifiServiceImpl.getWifiApEnabledState());

        // send an ap state change to verify WifiServiceImpl is updated
        verifyApRegistration();
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL);
        mLooper.dispatchAll();

        assertEquals(WifiManager.WIFI_AP_STATE_FAILED, mWifiServiceImpl.getWifiApEnabledState());
    }

    /**
     * Ensure we do not allow unpermitted callers to get the wifi ap state.
     */
    @Test
    public void testGetWifiApEnabledPermissionDenied() {
        // we should not be able to get the state
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(ACCESS_WIFI_STATE),
                                                eq("WifiService"));

        try {
            mWifiServiceImpl.getWifiApEnabledState();
            fail("expected SecurityException");
        } catch (SecurityException expected) { }
    }

    /**
     * Make sure we do start WifiController (wifi disabled) if the device is already decrypted.
     */
    @Test
    public void testWifiControllerStartsWhenDeviceBootsWithWifiDisabled() {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mWifiConfigManager).loadFromStore();
        verify(mActiveModeWarden).start();
        verify(mActiveModeWarden, never()).wifiToggled(any());
    }

    @Test
    public void testWifiVerboseLoggingInitialization() {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiSettingsConfigStore.get(eq(WIFI_VERBOSE_LOGGING_ENABLED))).thenReturn(true);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mWifiConfigManager).loadFromStore();
        verify(mActiveModeWarden).enableVerboseLogging(true);
        verify(mActiveModeWarden).start();
    }

    /**
     * Make sure we do start WifiController (wifi enabled) if the device is already decrypted.
     */
    @Test
    public void testWifiFullyStartsWhenDeviceBootsWithWifiEnabled() {
        when(mSettingsStore.handleWifiToggled(true)).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        when(mClientModeManager.syncGetWifiState()).thenReturn(WIFI_STATE_DISABLED);
        when(mContext.getPackageName()).thenReturn(ANDROID_SYSTEM_PACKAGE);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mWifiConfigManager).loadFromStore();
        verify(mActiveModeWarden).start();
    }

    /**
     * Verify caller with proper permission can call startSoftAp.
     */
    @Test
    public void testStartSoftApWithPermissionsAndNullConfig() {
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startSoftAp(null, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertNull(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify caller with proper permissions but an invalid config does not start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndInvalidConfig() {
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startSoftAp(mApConfig, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertFalse(result);
        verify(mActiveModeWarden, never()).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
    }

    /**
     * Verify caller with proper permission and valid config does start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndValidConfig() {
        WifiConfiguration config = createValidWifiApConfiguration();
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startSoftAp(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(
                mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        WifiConfigurationTestUtil.assertConfigurationEqualForSoftAp(
                config,
                mSoftApModeConfigCaptor.getValue().getSoftApConfiguration().toWifiConfiguration());
    }

    /**
     * Verify a SecurityException is thrown when a caller without the correct permission attempts to
     * start softap.
     */
    @Test(expected = SecurityException.class)
    public void testStartSoftApWithoutPermissionThrowsException() throws Exception {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK), any());
        mLooper.startAutoDispatch();
        mWifiServiceImpl.startSoftAp(null, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Verify that startSoftAP() succeeds if the caller does not have the NETWORK_STACK permission
     * but does have the MAINLINE_NETWORK_STACK permission.
     */
    @Test
    public void testStartSoftApWithoutNetworkStackWithMainlineNetworkStackSucceeds() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        WifiConfiguration config = createValidWifiApConfiguration();
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startSoftAp(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        WifiConfigurationTestUtil.assertConfigurationEqualForSoftAp(
                config,
                mSoftApModeConfigCaptor.getValue().getSoftApConfiguration().toWifiConfiguration());
        verify(mContext).enforceCallingOrSelfPermission(
                eq(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK), any());
    }

    /**
     * Verify that startSoftAp() with valid config succeeds after a failed call
     */
    @Test
    public void testStartSoftApWithValidConfigSucceedsAfterFailure() {
        // First initiate a failed call
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startSoftAp(mApConfig, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden, never()).startSoftAp(any(), any());

        // Next attempt a valid config
        WifiConfiguration config = createValidWifiApConfiguration();
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startSoftAp(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        WifiConfigurationTestUtil.assertConfigurationEqualForSoftAp(
                config,
                mSoftApModeConfigCaptor.getValue().getSoftApConfiguration().toWifiConfiguration());
    }

    /**
     * Verify caller with proper permission can call startTetheredHotspot.
     */
    @Test
    public void testStartTetheredHotspotWithPermissionsAndNullConfig() {
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(null, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertNull(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify caller with proper permissions but an invalid config does not start softap.
     */
    @Test
    public void testStartTetheredHotspotWithPermissionsAndInvalidConfig() {
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(
                new SoftApConfiguration.Builder().build(), TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertFalse(result);
        verify(mActiveModeWarden, never()).startSoftAp(any(), any());
    }

    /**
     * Verify caller with proper permission and valid config does start softap.
     */
    @Test
    public void testStartTetheredHotspotWithPermissionsAndValidConfig() {
        SoftApConfiguration config = createValidSoftApConfiguration();
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify isWifiBandSupported for 5GHz with an overlay override config
     */
    @Test
    public void testIsWifiBandSupported5gWithOverride() throws Exception {
        when(mResources.getBoolean(R.bool.config_wifi5ghzSupport)).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.is5GHzBandSupported());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWifiNative, never()).getChannelsForBand(anyInt());
    }

    /**
     * Verify isWifiBandSupported for 6GHz with an overlay override config
     */
    @Test
    public void testIsWifiBandSupported6gWithOverride() throws Exception {
        when(mResources.getBoolean(R.bool.config_wifi6ghzSupport)).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.is6GHzBandSupported());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWifiNative, never()).getChannelsForBand(anyInt());
    }

    /**
     * Verify isWifiBandSupported for 5GHz with no overlay override config no channels
     */
    @Test
    public void testIsWifiBandSupported5gNoOverrideNoChannels() throws Exception {
        final int[] emptyArray = {};
        when(mResources.getBoolean(R.bool.config_wifi5ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(anyInt())).thenReturn(emptyArray);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.is5GHzBandSupported());
        mLooper.stopAutoDispatch();
        verify(mWifiNative).getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
    }

    /**
     * Verify isWifiBandSupported for 5GHz with no overlay override config with channels
     */
    @Test
    public void testIsWifiBandSupported5gNoOverrideWithChannels() throws Exception {
        final int[] channelArray = {5170};
        when(mResources.getBoolean(R.bool.config_wifi5ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(anyInt())).thenReturn(channelArray);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.is5GHzBandSupported());
        mLooper.stopAutoDispatch();
        verify(mWifiNative).getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
    }

    /**
     * Verify isWifiBandSupported for 6GHz with no overlay override config no channels
     */
    @Test
    public void testIsWifiBandSupported6gNoOverrideNoChannels() throws Exception {
        final int[] emptyArray = {};
        when(mResources.getBoolean(R.bool.config_wifi6ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(anyInt())).thenReturn(emptyArray);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.is6GHzBandSupported());
        mLooper.stopAutoDispatch();
        verify(mWifiNative).getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ);
    }

    /**
     * Verify isWifiBandSupported for 6GHz with no overlay override config with channels
     */
    @Test
    public void testIsWifiBandSupported6gNoOverrideWithChannels() throws Exception {
        final int[] channelArray = {6420};
        when(mResources.getBoolean(R.bool.config_wifi6ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(anyInt())).thenReturn(channelArray);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.is6GHzBandSupported());
        mLooper.stopAutoDispatch();
        verify(mWifiNative).getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ);
    }

    private void setup5GhzSupported() {
        when(mResources.getBoolean(R.bool.config_wifi5ghzSupport)).thenReturn(true);
    }

    private void setup5GhzUnsupported() {
        when(mResources.getBoolean(R.bool.config_wifi5ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ)).thenReturn(new int[0]);
    }

    private void setup6GhzSupported() {
        when(mResources.getBoolean(R.bool.config_wifi6ghzSupport)).thenReturn(true);
    }

    private void setup6GhzUnsupported() {
        when(mResources.getBoolean(R.bool.config_wifi6ghzSupport)).thenReturn(false);
        when(mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ)).thenReturn(new int[0]);
    }

    /**
     * Verify attempt to start softAp with a supported 5GHz band succeeds.
     */
    @Test
    public void testStartTetheredHotspotWithSupported5gBand() {
        setup5GhzSupported();

        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_5GHZ)
                .build();

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify attempt to start softAp with a non-supported 5GHz band fails.
     */
    @Test
    public void testStartTetheredHotspotWithUnSupported5gBand() {
        setup5GhzUnsupported();

        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_5GHZ)
                .build();

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertFalse(result);
        verify(mActiveModeWarden, never()).startSoftAp(any(), any());
    }

    /**
     * Verify attempt to start softAp with a supported 6GHz band succeeds.
     */
    @Test
    public void testStartTetheredHotspotWithSupported6gBand() {
        when(mResources.getBoolean(
                eq(R.bool.config_wifiSoftap6ghzSupported)))
                .thenReturn(true);
        setup6GhzSupported();

        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_6GHZ)
                .build();

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify attempt to start softAp with a non-supported 6GHz band fails.
     */
    @Test
    public void testStartTetheredHotspotWithUnSupported6gBand() {
        when(mResources.getBoolean(
                eq(R.bool.config_wifiSoftap6ghzSupported)))
                .thenReturn(false);
        setup6GhzSupported();

        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_6GHZ)
                .build();

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertFalse(result);
        verify(mActiveModeWarden, never()).startSoftAp(any(), any());
    }

    /**
     * Verify attempt to start softAp with a supported band succeeds.
     */
    @Test
    public void testStartTetheredHotspotWithSupportedBand() {
        setup5GhzSupported();

        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_5GHZ)
                .build();

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertTrue(result);
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify a SecurityException is thrown when a caller without the correct permission attempts to
     * start softap.
     */
    @Test(expected = SecurityException.class)
    public void testStartTetheredHotspotWithoutPermissionThrowsException() throws Exception {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK), any());
        mWifiServiceImpl.startTetheredHotspot(null, TEST_PACKAGE_NAME);
    }

    /**
     * Verify that startTetheredHotspot() succeeds if the caller does not have the
     * NETWORK_STACK permission but does have the MAINLINE_NETWORK_STACK permission.
     */
    @Test
    public void testStartTetheredHotspotWithoutNetworkStackWithMainlineNetworkStackSucceeds() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        SoftApConfiguration config = createValidSoftApConfiguration();
        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME);
        assertTrue(result);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
        verify(mContext).enforceCallingOrSelfPermission(
                eq(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK), any());
    }

    /**
     * Verify a valied call to startTetheredHotspot succeeds after a failed call.
     */
    @Test
    public void testStartTetheredHotspotWithValidConfigSucceedsAfterFailedCall() {
        // First issue an invalid call
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startTetheredHotspot(
                new SoftApConfiguration.Builder().build(), TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden, never()).startSoftAp(any(), any());

        // Now attempt a successful call
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startTetheredHotspot(null, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertNull(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
    }

    /**
     * Verify caller with proper permission can call stopSoftAp.
     */
    @Test
    public void testStopSoftApWithPermissions() {
        boolean result = mWifiServiceImpl.stopSoftAp();
        assertTrue(result);
        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_TETHERED);
    }

    /**
     * Verify SecurityException is thrown when a caller without the correct permission attempts to
     * stop softap.
     */
    @Test(expected = SecurityException.class)
    public void testStopSoftApWithoutPermissionThrowsException() throws Exception {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK), any());
        mWifiServiceImpl.stopSoftAp();
    }

    /**
     * Ensure that we handle app ops check failure when handling scan request.
     */
    @Test
    public void testStartScanFailureAppOpsIgnored() {
        doReturn(AppOpsManager.MODE_IGNORED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), SCAN_PACKAGE_NAME);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Ensure that we handle scan access permission check failure when handling scan request.
     */
    @Test
    public void testStartScanFailureInCanAccessScanResultsPermission() {
        doThrow(new SecurityException()).when(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(SCAN_PACKAGE_NAME, TEST_FEATURE_ID, Process.myUid(),
                        null);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Ensure that we handle scan request failure when posting the runnable to handler fails.
     */
    @Test
    public void testStartScanFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy, never()).startScan(anyInt(), eq(SCAN_PACKAGE_NAME));
    }

    /**
     * Ensure that we handle scan request failure from ScanRequestProxy fails.
     */
    @Test
    public void testStartScanFailureFromScanRequestProxy() {
        when(mScanRequestProxy.startScan(anyInt(), anyString())).thenReturn(false);

        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy).startScan(Binder.getCallingUid(), SCAN_PACKAGE_NAME);
    }

    private void setupForGetConnectionInfo() {
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        wifiInfo.setBSSID(TEST_BSSID);
        wifiInfo.setNetworkId(TEST_NETWORK_ID);
        wifiInfo.setFQDN(TEST_FQDN);
        wifiInfo.setProviderFriendlyName(TEST_FRIENDLY_NAME);
        when(mClientModeManager.syncRequestConnectionInfo()).thenReturn(wifiInfo);
    }

    /**
     * Test that connected SSID and BSSID are not exposed to an app that does not have the
     * appropriate permissions.
     */
    @Test
    public void testConnectedIdsAreHiddenFromAppWithoutPermission() throws Exception {
        setupForGetConnectionInfo();

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), nullable(String.class), anyInt(), nullable(String.class));

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE, TEST_FEATURE_ID);

        assertEquals(WifiManager.UNKNOWN_SSID, connectionInfo.getSSID());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, connectionInfo.getBSSID());
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, connectionInfo.getNetworkId());
        assertNull(connectionInfo.getPasspointFqdn());
        assertNull(connectionInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Test that connected SSID and BSSID are not exposed to an app that does not have the
     * appropriate permissions, when enforceCanAccessScanResults raises a SecurityException.
     */
    @Test
    public void testConnectedIdsAreHiddenOnSecurityException() throws Exception {
        setupForGetConnectionInfo();

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), nullable(String.class), anyInt(), nullable(String.class));

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE, TEST_FEATURE_ID);

        assertEquals(WifiManager.UNKNOWN_SSID, connectionInfo.getSSID());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, connectionInfo.getBSSID());
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, connectionInfo.getNetworkId());
        assertNull(connectionInfo.getPasspointFqdn());
        assertNull(connectionInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Test that connected SSID and BSSID are exposed to an app that does have the
     * appropriate permissions.
     */
    @Test
    public void testConnectedIdsAreVisibleFromPermittedApp() throws Exception {
        setupForGetConnectionInfo();

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE, TEST_FEATURE_ID);

        assertEquals(TEST_SSID_WITH_QUOTES, connectionInfo.getSSID());
        assertEquals(TEST_BSSID, connectionInfo.getBSSID());
        assertEquals(TEST_NETWORK_ID, connectionInfo.getNetworkId());
        assertEquals(TEST_FQDN, connectionInfo.getPasspointFqdn());
        assertEquals(TEST_FRIENDLY_NAME, connectionInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Test that configured network list are exposed empty list to an app that does not have the
     * appropriate permissions.
     */
    @Test
    public void testConfiguredNetworkListAreEmptyFromAppWithoutPermission() throws Exception {
        when(mWifiConfigManager.getSavedNetworks(anyInt()))
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        // no permission = target SDK=Q && not a carrier app
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(anyString())).thenReturn(
                TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);

        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);

        assertEquals(0, configs.getList().size());
    }

    /**
     * Test that configured network list are exposed empty list to an app that does not have the
     * appropriate permissions, when enforceCanAccessScanResults raises a SecurityException.
     */
    @Test
    public void testConfiguredNetworkListAreEmptyOnSecurityException() throws Exception {
        when(mWifiConfigManager.getSavedNetworks(anyInt()))
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), nullable(String.class), anyInt(), nullable(String.class));

        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);

        assertEquals(0, configs.getList().size());

    }

    /**
     * Test that configured network list are exposed to an app that does have the
     * appropriate permissions.
     */
    @Test
    public void testConfiguredNetworkListAreVisibleFromPermittedApp() throws Exception {
        when(mWifiConfigManager.getSavedNetworks(anyInt()))
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);

        mLooper.startAutoDispatch();
        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiConfigManager).getSavedNetworks(eq(Process.WIFI_UID));
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                TEST_WIFI_CONFIGURATION_LIST, configs.getList());
    }


    /**
     * Test that privileged network list are exposed null to an app that does not have the
     * appropriate permissions.
     */
    @Test
    public void testPrivilegedConfiguredNetworkListAreEmptyFromAppWithoutPermission() {
        when(mWifiConfigManager.getConfiguredNetworksWithPasswords())
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), nullable(String.class), anyInt(), nullable(String.class));

        mLooper.startAutoDispatch();
        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getPrivilegedConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertNull(configs);
    }

    /**
     * Test that privileged network list are exposed null to an app that does not have the
     * appropriate permissions, when enforceCanAccessScanResults raises a SecurityException.
     */
    @Test
    public void testPrivilegedConfiguredNetworkListAreEmptyOnSecurityException() {
        when(mWifiConfigManager.getConfiguredNetworksWithPasswords())
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), nullable(String.class), anyInt(), nullable(String.class));

        mLooper.startAutoDispatch();
        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getPrivilegedConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertNull(configs);
    }

    /**
     * Test that privileged network list are exposed to an app that does have the
     * appropriate permissions (simulated by not throwing an exception for READ_WIFI_CREDENTIAL).
     */
    @Test
    public void testPrivilegedConfiguredNetworkListAreVisibleFromPermittedApp() {
        when(mWifiConfigManager.getConfiguredNetworksWithPasswords())
                .thenReturn(TEST_WIFI_CONFIGURATION_LIST);

        mLooper.startAutoDispatch();
        ParceledListSlice<WifiConfiguration> configs =
                mWifiServiceImpl.getPrivilegedConfiguredNetworks(TEST_PACKAGE, TEST_FEATURE_ID);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                TEST_WIFI_CONFIGURATION_LIST, configs.getList());
    }

    /**
     * Test fetching of scan results.
     */
    @Test
    public void testGetScanResults() {
        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);

        String packageName = "test.com";
        String featureId = "test.com.featureId";
        mLooper.startAutoDispatch();
        List<ScanResult> retrievedScanResultList = mWifiServiceImpl.getScanResults(packageName,
                featureId);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy).getScanResults();

        ScanTestUtil.assertScanResultsEquals(scanResults,
                retrievedScanResultList.toArray(new ScanResult[retrievedScanResultList.size()]));
    }

    /**
     * Ensure that we handle scan results failure when posting the runnable to handler fails.
     */
    @Test
    public void testGetScanResultsFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);

        String packageName = "test.com";
        String featureId = "test.com.featureId";
        mLooper.startAutoDispatch();
        List<ScanResult> retrievedScanResultList = mWifiServiceImpl.getScanResults(packageName,
                featureId);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy, never()).getScanResults();

        assertTrue(retrievedScanResultList.isEmpty());
    }

    /**
     * Test fetching of matching scan results with provided WifiNetworkSuggestion, but it doesn't
     * specify the scan results to be filtered.
     */
    @Test
    public void testGetMatchingScanResultsWithoutSpecifiedScanResults() {
        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);
        WifiNetworkSuggestion mockSuggestion = mock(WifiNetworkSuggestion.class);
        List<WifiNetworkSuggestion> matchingSuggestions = new ArrayList<>() {{
                add(mockSuggestion);
            }};
        Map<WifiNetworkSuggestion, List<ScanResult>> result = new HashMap<>() {{
                put(mockSuggestion, scanResultList);
            }};
        when(mWifiNetworkSuggestionsManager.getMatchingScanResults(eq(matchingSuggestions),
                eq(scanResultList))).thenReturn(result);

        String packageName = "test.com";
        String featureId = "test.com.featureId";
        mLooper.startAutoDispatch();
        Map<WifiNetworkSuggestion, List<ScanResult>> retrievedScanResults =
                mWifiServiceImpl.getMatchingScanResults(
                        matchingSuggestions, null, packageName, featureId);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        ScanTestUtil.assertScanResultsEquals(scanResults,
                retrievedScanResults.get(mockSuggestion)
                        .toArray(new ScanResult[retrievedScanResults.size()]));
    }

    /**
     * Test fetching of matching scan results with provided WifiNetworkSuggestion and ScanResults.
     */
    @Test
    public void testGetMatchingScanResultsWithSpecifiedScanResults() {
        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        WifiNetworkSuggestion mockSuggestion = mock(WifiNetworkSuggestion.class);
        List<WifiNetworkSuggestion> matchingSuggestions = new ArrayList<>() {{
                add(mockSuggestion);
            }};
        Map<WifiNetworkSuggestion, List<ScanResult>> result = new HashMap<>() {{
                put(mockSuggestion, scanResultList);
            }};
        when(mWifiNetworkSuggestionsManager.getMatchingScanResults(eq(matchingSuggestions),
                eq(scanResultList))).thenReturn(result);

        String packageName = "test.com";
        String featureId = "test.com.featureId";
        mLooper.startAutoDispatch();
        Map<WifiNetworkSuggestion, List<ScanResult>> retrievedScanResults =
                mWifiServiceImpl.getMatchingScanResults(
                        matchingSuggestions, scanResultList, packageName, featureId);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        ScanTestUtil.assertScanResultsEquals(scanResults,
                retrievedScanResults.get(mockSuggestion)
                        .toArray(new ScanResult[retrievedScanResults.size()]));
    }

    /**
     * Ensure that we handle failure when posting the runnable to handler fails.
     */
    @Test
    public void testGetMatchingScanResultsFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);
        WifiNetworkSuggestion mockSuggestion = mock(WifiNetworkSuggestion.class);
        List<WifiNetworkSuggestion> matchingSuggestions = new ArrayList<>() {{
                add(mockSuggestion);
            }};
        Map<WifiNetworkSuggestion, List<ScanResult>> result = new HashMap<>() {{
                put(mockSuggestion, scanResultList);
            }};
        when(mWifiNetworkSuggestionsManager.getMatchingScanResults(eq(matchingSuggestions),
                eq(scanResultList))).thenReturn(result);

        String packageName = "test.com";
        String featureId = "test.com.featureId";
        mLooper.startAutoDispatch();
        Map<WifiNetworkSuggestion, List<ScanResult>> retrievedScanResults =
                mWifiServiceImpl.getMatchingScanResults(
                        matchingSuggestions, null, packageName, featureId);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertTrue(retrievedScanResults.isEmpty());
    }

    private void setupLohsPermissions() {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mFrameworkFacade.isAppForeground(any(), anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestrictionForUser(
                eq(UserManager.DISALLOW_CONFIG_TETHERING), any()))
                .thenReturn(false);
    }

    private void registerLOHSRequestFull() {
        setupLohsPermissions();
        int result = mWifiServiceImpl.startLocalOnlyHotspot(mLohsCallback, TEST_PACKAGE_NAME,
                TEST_FEATURE_ID, null);
        assertEquals(LocalOnlyHotspotCallback.REQUEST_REGISTERED, result);
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
    }

    /**
     * Verify that the call to startLocalOnlyHotspot returns REQUEST_REGISTERED when successfully
     * called.
     */
    @Test
    public void testStartLocalOnlyHotspotSingleRegistrationReturnsRequestRegistered() {
        registerLOHSRequestFull();
        // Use settings worksouce.
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(TEST_SETTINGS_WORKSOURCE));
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have the CHANGE_WIFI_STATE permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutCorrectPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have Location permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationPermission() {
        doThrow(new SecurityException())
                .when(mWifiPermissionsUtil).enforceLocationPermission(eq(TEST_PACKAGE_NAME),
                                                                      eq(TEST_FEATURE_ID),
                                                                      anyInt());
        mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if Location mode is
     * disabled.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationEnabled() {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);
        mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
    }

    /**
     * Only start LocalOnlyHotspot if the caller is the foreground app at the time of the request.
     */
    @Test
    public void testStartLocalOnlyHotspotFailsIfRequestorNotForegroundApp() throws Exception {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        when(mFrameworkFacade.isAppForeground(any(), anyInt())).thenReturn(false);
        int result = mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
        assertEquals(LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE, result);
    }

    /**
     * Only start tethering if we are not tethering.
     */
    @Test
    public void testTetheringDoesNotStartWhenAlreadyTetheringActive() throws Exception {
        WifiConfiguration config = createValidWifiApConfiguration();
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startSoftAp(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        WifiConfigurationTestUtil.assertConfigurationEqualForSoftAp(
                config,
                mSoftApModeConfigCaptor.getValue().getSoftApConfiguration().toWifiConfiguration());
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        assertEquals(WIFI_AP_STATE_ENABLED, mWifiServiceImpl.getWifiApEnabledState());
        reset(mActiveModeWarden);

        // Start another session without a stop, that should fail.
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startSoftAp(
                createValidWifiApConfiguration(), TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyNoMoreInteractions(mActiveModeWarden);
    }

    /**
     * Only start tethering if we are not tethering in new API: startTetheredHotspot.
     */
    @Test
    public void testStartTetheredHotspotDoesNotStartWhenAlreadyTetheringActive() throws Exception {
        SoftApConfiguration config = createValidSoftApConfiguration();
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatch();
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        assertThat(config).isEqualTo(mSoftApModeConfigCaptor.getValue().getSoftApConfiguration());
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        assertEquals(WIFI_AP_STATE_ENABLED, mWifiServiceImpl.getWifiApEnabledState());
        reset(mActiveModeWarden);

        // Start another session without a stop, that should fail.
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startTetheredHotspot(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyNoMoreInteractions(mActiveModeWarden);
    }

    /**
     * Only start LocalOnlyHotspot if we are not tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenAlreadyTethering() throws Exception {
        WifiConfiguration config = createValidWifiApConfiguration();
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startSoftAp(config, TEST_PACKAGE_NAME));
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mFrameworkFacade.isAppForeground(any(), anyInt())).thenReturn(true);
        mLooper.dispatchAll();
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
        assertEquals(ERROR_INCOMPATIBLE_MODE, returnCode);
    }

    /**
     * Only start LocalOnlyHotspot if admin setting does not disallow tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenTetheringDisallowed() throws Exception {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        when(mFrameworkFacade.isAppForeground(any(), anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestrictionForUser(
                eq(UserManager.DISALLOW_CONFIG_TETHERING), any()))
                .thenReturn(true);
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
        assertEquals(ERROR_TETHERING_DISALLOWED, returnCode);
    }

    /**
     * Verify that callers can only have one registered LOHS request.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartLocalOnlyHotspotThrowsExceptionWhenCallerAlreadyRegistered() {
        registerLOHSRequestFull();

        // now do the second request that will fail
        mWifiServiceImpl.startLocalOnlyHotspot(
                mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot does not do anything when there aren't any
     * registered callers.
     */
    @Test
    public void testStopLocalOnlyHotspotDoesNothingWithoutRegisteredRequests() throws Exception {
        // allow test to proceed without a permission check failure
        mWifiServiceImpl.stopLocalOnlyHotspot();
        mLooper.dispatchAll();
        // there is nothing registered, so this shouldn't do anything
        verify(mActiveModeWarden, never()).stopSoftAp(anyInt());
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot does not do anything when one caller unregisters
     * but there is still an active request
     */
    @Test
    public void testStopLocalOnlyHotspotDoesNothingWithRemainingRequest() throws Exception {
        // register a request that will remain after the stopLOHS call
        mWifiServiceImpl.registerLOHSForTest(mPid, mRequestInfo);

        setupLocalOnlyHotspot();

        // Since we are calling with the same pid, the second register call will be removed
        mWifiServiceImpl.stopLocalOnlyHotspot();
        mLooper.dispatchAll();
        // there is still a valid registered request - do not tear down LOHS
        verify(mActiveModeWarden, never()).stopSoftAp(anyInt());
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot sends a message to WifiController to stop
     * the softAp when there is one registered caller when that caller is removed.
     */
    @Test
    public void testStopLocalOnlyHotspotTriggersStopWithOneRegisteredRequest() throws Exception {
        setupLocalOnlyHotspot();

        verify(mActiveModeWarden).startSoftAp(any(), any());

        mWifiServiceImpl.stopLocalOnlyHotspot();
        mLooper.dispatchAll();

        // No permission check required for change_wifi_state.
        verify(mContext, never()).enforceCallingOrSelfPermission(
                eq("android.Manifest.permission.CHANGE_WIFI_STATE"), anyString());

        // there is was only one request registered, we should tear down LOHS
        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
    }

    /**
     * Verify that by default startLocalOnlyHotspot starts access point at 2 GHz.
     */
    @Test
    public void testStartLocalOnlyHotspotAt2Ghz() {
        registerLOHSRequestFull();
        verifyLohsBand(SoftApConfiguration.BAND_2GHZ);
    }

    /**
     * Verify that startLocalOnlyHotspot will start access point at 6 GHz if properly configured
     * and if feasible, even if the 5GHz is enabled.
     */
    @Test
    public void testStartLocalOnlyHotspotAt6Ghz() {
        when(mResources.getBoolean(
                eq(R.bool.config_wifi_local_only_hotspot_5ghz)))
                .thenReturn(true);
        when(mResources.getBoolean(
                eq(R.bool.config_wifiLocalOnlyHotspot6ghz)))
                .thenReturn(true);
        setup5GhzSupported();
        setup6GhzSupported();

        when(mResources.getBoolean(
                eq(R.bool.config_wifiSoftap6ghzSupported)))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(true);

        mLooper.startAutoDispatch();
        registerLOHSRequestFull();
        verifyLohsBand(SoftApConfiguration.BAND_6GHZ);
    }

    /**
     * Verify that startLocalOnlyHotspot will start access point at 5 GHz if both 5GHz and 6GHz
     * are enabled, but SoftAp is not supported for 6GHz.
     */
    @Test
    public void testStartLocalOnlyHotspotAt5Ghz() {
        when(mResources.getBoolean(
                eq(R.bool.config_wifi_local_only_hotspot_5ghz)))
                .thenReturn(true);

        setup5GhzSupported();
        setup6GhzSupported();

        when(mResources.getBoolean(
                eq(R.bool.config_wifiSoftap6ghzSupported)))
                .thenReturn(false);
        when(mResources.getBoolean(
                eq(R.bool.config_wifiLocalOnlyHotspot6ghz)))
                .thenReturn(true);

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(true);

        mLooper.startAutoDispatch();
        registerLOHSRequestFull();
        verifyLohsBand(SoftApConfiguration.BAND_5GHZ);
    }

    private void verifyLohsBand(int expectedBand) {
        verify(mActiveModeWarden).startSoftAp(mSoftApModeConfigCaptor.capture(),
                eq(TEST_SETTINGS_WORKSOURCE));
        final SoftApConfiguration configuration =
                mSoftApModeConfigCaptor.getValue().getSoftApConfiguration();
        assertNotNull(configuration);
        assertEquals(expectedBand, configuration.getBand());
    }

    private static class FakeLohsCallback extends ILocalOnlyHotspotCallback.Stub {
        boolean mIsStarted = false;
        SoftApConfiguration mSoftApConfig = null;

        @Override
        public void onHotspotStarted(SoftApConfiguration softApConfig) {
            mIsStarted = true;
            this.mSoftApConfig = softApConfig;
        }

        @Override
        public void onHotspotStopped() {
            mIsStarted = false;
            mSoftApConfig = null;
        }

        @Override
        public void onHotspotFailed(int i) {
            mIsStarted = false;
            mSoftApConfig = null;
        }
    }

    private void setupForCustomLohs() {
        setupLohsPermissions();
        when(mContext.checkPermission(eq(Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        setupWardenForCustomLohs();
    }

    private void setupWardenForCustomLohs() {
        doAnswer(invocation -> {
            changeLohsState(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR);
            mWifiServiceImpl.updateInterfaceIpState(mLohsInterfaceName, IFACE_IP_MODE_LOCAL_ONLY);
            return null;
        }).when(mActiveModeWarden).startSoftAp(any(), any());
    }

    @Test(expected = SecurityException.class)
    public void testCustomLohs_FailsWithoutPermission() {
        SoftApConfiguration customConfig = new SoftApConfiguration.Builder()
                .setSsid("customConfig")
                .build();
        // set up basic permissions, but not NETWORK_SETUP_WIZARD
        setupLohsPermissions();
        setupWardenForCustomLohs();
        mWifiServiceImpl.startLocalOnlyHotspot(mLohsCallback, TEST_PACKAGE_NAME, TEST_FEATURE_ID,
                customConfig);
    }

    private static void nopDeathCallback(LocalOnlyHotspotRequestInfo requestor) {
    }

    @Test
    public void testCustomLohs_ExclusiveAfterShared() {
        FakeLohsCallback sharedCallback = new FakeLohsCallback();
        FakeLohsCallback exclusiveCallback = new FakeLohsCallback();
        SoftApConfiguration exclusiveConfig = new SoftApConfiguration.Builder()
                .setSsid("customSsid")
                .build();

        setupForCustomLohs();
        mWifiServiceImpl.registerLOHSForTest(mPid, new LocalOnlyHotspotRequestInfo(
                new WorkSource(), sharedCallback, WifiServiceImplTest::nopDeathCallback, null));
        assertThat(mWifiServiceImpl.startLocalOnlyHotspot(exclusiveCallback, TEST_PACKAGE_NAME,
                TEST_FEATURE_ID, exclusiveConfig)).isEqualTo(ERROR_GENERIC);
        mLooper.dispatchAll();

        assertThat(sharedCallback.mIsStarted).isTrue();
        assertThat(exclusiveCallback.mIsStarted).isFalse();
    }

    @Test
    public void testCustomLohs_ExclusiveBeforeShared() {
        FakeLohsCallback sharedCallback = new FakeLohsCallback();
        FakeLohsCallback exclusiveCallback = new FakeLohsCallback();
        SoftApConfiguration exclusiveConfig = new SoftApConfiguration.Builder()
                .setSsid("customSsid")
                .build();

        setupForCustomLohs();
        mWifiServiceImpl.registerLOHSForTest(mPid, new LocalOnlyHotspotRequestInfo(
                new WorkSource(), exclusiveCallback, WifiServiceImplTest::nopDeathCallback,
                exclusiveConfig));
        assertThat(mWifiServiceImpl.startLocalOnlyHotspot(sharedCallback, TEST_PACKAGE_NAME,
                TEST_FEATURE_ID, null)).isEqualTo(ERROR_GENERIC);
        mLooper.dispatchAll();

        assertThat(exclusiveCallback.mIsStarted).isTrue();
        assertThat(sharedCallback.mIsStarted).isFalse();
    }

    @Test
    public void testCustomLohs_Wpa2() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("customSsid")
                .setPassphrase("passphrase", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
        FakeLohsCallback callback = new FakeLohsCallback();

        setupForCustomLohs();
        assertThat(
                mWifiServiceImpl.startLocalOnlyHotspot(callback, TEST_PACKAGE_NAME, TEST_FEATURE_ID,
                        config)).isEqualTo(REQUEST_REGISTERED);
        mLooper.dispatchAll();

        // Use app's worksouce.
        verify(mActiveModeWarden).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));

        assertThat(callback.mIsStarted).isTrue();
        assertThat(callback.mSoftApConfig.getSsid()).isEqualTo("customSsid");
        assertThat(callback.mSoftApConfig.getSecurityType())
                .isEqualTo(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        assertThat(callback.mSoftApConfig.getPassphrase()).isEqualTo("passphrase");
    }

    @Test
    public void testCustomLohs_Open() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setSsid("customSsid")
                .build();
        FakeLohsCallback callback = new FakeLohsCallback();

        setupForCustomLohs();
        assertThat(
                mWifiServiceImpl.startLocalOnlyHotspot(callback, TEST_PACKAGE_NAME, TEST_FEATURE_ID,
                        config)).isEqualTo(REQUEST_REGISTERED);
        mLooper.dispatchAll();

        // Use app's worksouce.
        verify(mActiveModeWarden).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));

        assertThat(callback.mIsStarted).isTrue();
        assertThat(callback.mSoftApConfig.getSsid()).isEqualTo("customSsid");
        assertThat(callback.mSoftApConfig.getSecurityType())
                .isEqualTo(SoftApConfiguration.SECURITY_TYPE_OPEN);
        assertThat(callback.mSoftApConfig.getPassphrase()).isNull();
    }

    @Test
    public void testCustomLohs_GeneratesSsidIfAbsent() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setPassphrase("passphrase", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
        FakeLohsCallback callback = new FakeLohsCallback();

        setupForCustomLohs();
        assertThat(
                mWifiServiceImpl.startLocalOnlyHotspot(callback, TEST_PACKAGE_NAME, TEST_FEATURE_ID,
                        config)).isEqualTo(REQUEST_REGISTERED);
        mLooper.dispatchAll();

        // Use app's worksouce.
        verify(mActiveModeWarden).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));

        assertThat(callback.mIsStarted).isTrue();
        assertThat(callback.mSoftApConfig.getSsid()).isNotEmpty();
    }

    @Test
    public void testCustomLohs_ForwardsBssid() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setBssid(MacAddress.fromString("aa:bb:cc:dd:ee:ff"))
                .build();
        FakeLohsCallback callback = new FakeLohsCallback();

        setupForCustomLohs();
        assertThat(
                mWifiServiceImpl.startLocalOnlyHotspot(callback, TEST_PACKAGE_NAME, TEST_FEATURE_ID,
                        config)).isEqualTo(REQUEST_REGISTERED);
        mLooper.dispatchAll();

        // Use app's worksouce.
        verify(mActiveModeWarden).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));

        assertThat(callback.mIsStarted).isTrue();
        assertThat(callback.mSoftApConfig.getBssid().toString())
                .ignoringCase().isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    /**
         * Verify that WifiServiceImpl does not send the stop ap message if there were no
         * pending LOHS requests upon a binder death callback.
         */
    @Test
    public void testServiceImplNotCalledWhenBinderDeathTriggeredNoRequests() {
        LocalOnlyRequestorCallback binderDeathCallback =
                mWifiServiceImpl.new LocalOnlyRequestorCallback();

        binderDeathCallback.onLocalOnlyHotspotRequestorDeath(mRequestInfo);
        verify(mActiveModeWarden, never()).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
    }

    /**
     * Verify that WifiServiceImpl does not send the stop ap message if there are remaining
     * registered LOHS requests upon a binder death callback.  Additionally verify that softap mode
     * will be stopped if that remaining request is removed (to verify the binder death properly
     * cleared the requestor that died).
     */
    @Test
    public void testServiceImplNotCalledWhenBinderDeathTriggeredWithRequests() throws Exception {
        LocalOnlyRequestorCallback binderDeathCallback =
                mWifiServiceImpl.new LocalOnlyRequestorCallback();

        // registering a request directly from the test will not trigger a message to start
        // softap mode
        mWifiServiceImpl.registerLOHSForTest(mPid, mRequestInfo);

        setupLocalOnlyHotspot();

        binderDeathCallback.onLocalOnlyHotspotRequestorDeath(mRequestInfo);
        verify(mActiveModeWarden, never()).stopSoftAp(anyInt());

        reset(mActiveModeWarden);

        // now stop as the second request and confirm CMD_SET_AP will be sent to make sure binder
        // death requestor was removed
        mWifiServiceImpl.stopLocalOnlyHotspot();
        mLooper.dispatchAll();
        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
    }

    /**
     * Verify that a call to registerSoftApCallback throws a SecurityException if the caller does
     * not have neither NETWORK_SETTINGS nor MAINLINE_NETWORK_STACK permission.
     */
    @Test(expected = SecurityException.class)
    public void registerSoftApCallbackThrowsSecurityExceptionOnMissingPermissions() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_SETTINGS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        final int callbackIdentifier = 1;
        mWifiServiceImpl.registerSoftApCallback(
                mAppBinder, mClientSoftApCallback, callbackIdentifier);
    }

    /**
     * Verify that a call to registerSoftApCallback throws an IllegalArgumentException if the
     * parameters are not provided.
     */
    @Test
    public void registerSoftApCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            final int callbackIdentifier = 1;
            mWifiServiceImpl.registerSoftApCallback(mAppBinder, null, callbackIdentifier);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterSoftApCallback throws a SecurityException if the caller does
     * not have neither NETWORK_SETTINGS nor MAINLINE_NETWORK_STACK permission.
     */
    @Test(expected = SecurityException.class)
    public void unregisterSoftApCallbackThrowsSecurityExceptionOnMissingPermissions() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.NETWORK_SETTINGS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        final int callbackIdentifier = 1;
        mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
    }

    /**
     * Verifies that we handle softap callback registration failure if we encounter an exception
     * while linking to death.
     */
    @Test
    public void registerSoftApCallbackFailureOnLinkToDeath() throws Exception {
        doThrow(new RemoteException())
                .when(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback, 1);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onStateChanged(WIFI_AP_STATE_DISABLED, 0);
        verify(mClientSoftApCallback, never()).onConnectedClientsChanged(any());
        verify(mClientSoftApCallback, never()).onInfoChanged(any());
        verify(mClientSoftApCallback, never()).onCapabilityChanged(any());
    }


    /**
     * Registers a soft AP callback, then verifies that the current soft AP state and num clients
     * are sent to caller immediately after callback is registered.
     */
    private void registerSoftApCallbackAndVerify(ISoftApCallback callback, int callbackIdentifier)
            throws Exception {
        registerSoftApCallbackAndVerify(mAppBinder, callback, callbackIdentifier);
    }

    /**
     * Registers a soft AP callback, then verifies that the current soft AP state and num clients
     * are sent to caller immediately after callback is registered.
     */
    private void registerSoftApCallbackAndVerify(IBinder binder, ISoftApCallback callback,
                                                 int callbackIdentifier) throws Exception {
        mWifiServiceImpl.registerSoftApCallback(binder, callback, callbackIdentifier);
        mLooper.dispatchAll();
        verify(callback).onStateChanged(WIFI_AP_STATE_DISABLED, 0);
        verify(callback).onConnectedClientsChanged(Mockito.<WifiClient>anyList());
        verify(callback).onInfoChanged(new SoftApInfo());
        verify(callback).onCapabilityChanged(ApConfigUtil.updateCapabilityFromResource(mContext));
        // Don't need to invoke callback when register.
        verify(callback, never()).onBlockedClientConnecting(any(), anyInt());
    }

    /**
     * Verify that registering twice with same callbackIdentifier will replace the first callback.
     */
    @Test
    public void replacesOldCallbackWithNewCallbackWhenRegisteringTwice() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mAppBinder, mClientSoftApCallback, callbackIdentifier);
        registerSoftApCallbackAndVerify(
                mAnotherAppBinder, mAnotherSoftApCallback, callbackIdentifier);

        verify(mAppBinder).linkToDeath(any(), anyInt());
        verify(mAppBinder).unlinkToDeath(any(), anyInt());
        verify(mAnotherAppBinder).linkToDeath(any(), anyInt());
        verify(mAnotherAppBinder, never()).unlinkToDeath(any(), anyInt());

        final WifiClient testClient = new WifiClient(TEST_FACTORY_MAC_ADDR);
        final List<WifiClient> testClients = new ArrayList();
        testClients.add(testClient);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        // Verify only the second callback is being called
        verify(mClientSoftApCallback, never()).onConnectedClientsChanged(testClients);
        verify(mAnotherSoftApCallback).onConnectedClientsChanged(testClients);
    }

    /**
     * Verify that unregisterSoftApCallback removes callback from registered callbacks list
     */
    @Test
    public void unregisterSoftApCallbackRemovesCallback() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
        mLooper.dispatchAll();

        final WifiClient testClient = new WifiClient(TEST_FACTORY_MAC_ADDR);
        final List<WifiClient> testClients = new ArrayList();
        testClients.add(testClient);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onConnectedClientsChanged(testClients);
    }

    /**
     * Verify that unregisterSoftApCallback is no-op if callbackIdentifier not registered.
     */
    @Test
    public void unregisterSoftApCallbackDoesNotRemoveCallbackIfCallbackIdentifierNotMatching()
            throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        final int differentCallbackIdentifier = 2;
        mWifiServiceImpl.unregisterSoftApCallback(differentCallbackIdentifier);
        mLooper.dispatchAll();

        final WifiClient testClient = new WifiClient(TEST_FACTORY_MAC_ADDR);
        final List<WifiClient> testClients = new ArrayList();
        testClients.add(testClient);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onConnectedClientsChanged(testClients);
    }

    /**
     * Registers two callbacks, remove one then verify the right callback is being called on events.
     */
    @Test
    public void correctCallbackIsCalledAfterAddingTwoCallbacksAndRemovingOne() throws Exception {
        final int callbackIdentifier = 1;
        WifiClient testWifiClient = new WifiClient(MacAddress.fromString("22:33:44:55:66:77"));
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback,
                callbackIdentifier);
        mLooper.dispatchAll();

        // Change state from default before registering the second callback
        final List<WifiClient> testClients = new ArrayList();
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mStateMachineSoftApCallback.onInfoChanged(mTestSoftApInfo);
        mStateMachineSoftApCallback.onBlockedClientConnecting(testWifiClient, 0);


        // Register another callback and verify the new state is returned in the immediate callback
        final int anotherUid = 2;
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mAnotherSoftApCallback, anotherUid);
        mLooper.dispatchAll();
        verify(mAnotherSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        verify(mAnotherSoftApCallback).onConnectedClientsChanged(testClients);
        verify(mAnotherSoftApCallback).onInfoChanged(mTestSoftApInfo);
        // Verify only first callback will receive onBlockedClientConnecting since it call after
        // first callback register but before another callback register.
        verify(mClientSoftApCallback).onBlockedClientConnecting(testWifiClient, 0);
        verify(mAnotherSoftApCallback, never()).onBlockedClientConnecting(testWifiClient, 0);

        // unregister the fisrt callback
        mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
        mLooper.dispatchAll();

        // Update soft AP state and verify the remaining callback receives the event
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
        verify(mAnotherSoftApCallback).onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
    }

    /**
     * Verify that wifi service registers for callers BinderDeath event
     */
    @Test
    public void registersForBinderDeathOnRegisterSoftApCallback() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);
        verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    /**
     * Verify that we un-register the soft AP callback on receiving BinderDied event.
     */
    @Test
    public void unregistersSoftApCallbackOnBinderDied() throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> drCaptor =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);
        verify(mAppBinder).linkToDeath(drCaptor.capture(), anyInt());

        drCaptor.getValue().binderDied();
        mLooper.dispatchAll();
        verify(mAppBinder).unlinkToDeath(drCaptor.getValue(), 0);

        // Verify callback is removed from the list as well
        final WifiClient testClient = new WifiClient(TEST_FACTORY_MAC_ADDR);
        final List<WifiClient> testClients = new ArrayList();
        testClients.add(testClient);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onConnectedClientsChanged(testClients);
    }

    /**
     * Verify that soft AP callback is called on NumClientsChanged event
     */
    @Test
    public void callsRegisteredCallbacksOnConnectedClientsChangedEvent() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        final WifiClient testClient = new WifiClient(TEST_FACTORY_MAC_ADDR);
        final List<WifiClient> testClients = new ArrayList();
        testClients.add(testClient);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onConnectedClientsChanged(testClients);
    }

    /**
     * Verify that soft AP callback is called on SoftApStateChanged event
     */
    @Test
    public void callsRegisteredCallbacksOnSoftApStateChangedEvent() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verify that mSoftApState and mSoftApNumClients in WifiServiceImpl are being updated on soft
     * Ap events, even when no callbacks are registered.
     */
    @Test
    public void updatesSoftApStateAndConnectedClientsOnSoftApEvents() throws Exception {
        final List<WifiClient> testClients = new ArrayList();
        WifiClient testWifiClient = new WifiClient(MacAddress.fromString("22:33:44:55:66:77"));
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mStateMachineSoftApCallback.onConnectedClientsChanged(testClients);
        mStateMachineSoftApCallback.onInfoChanged(mTestSoftApInfo);
        mStateMachineSoftApCallback.onBlockedClientConnecting(testWifiClient, 0);

        // Register callback after num clients and soft AP are changed.
        final int callbackIdentifier = 1;
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback,
                callbackIdentifier);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        verify(mClientSoftApCallback).onConnectedClientsChanged(testClients);
        verify(mClientSoftApCallback).onInfoChanged(mTestSoftApInfo);
        // Don't need to invoke callback when register.
        verify(mClientSoftApCallback, never()).onBlockedClientConnecting(any(), anyInt());
    }

    private class IntentFilterMatcher implements ArgumentMatcher<IntentFilter> {
        @Override
        public boolean matches(IntentFilter filter) {
            return filter.hasAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        }
    }

    /**
     * Verify that onFailed is called for registered LOHS callers on SAP_START_FAILURE_GENERAL.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApFailureGeneric() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        registerLOHSRequestFull();

        changeLohsState(WIFI_AP_STATE_FAILED, WIFI_AP_STATE_DISABLED, SAP_START_FAILURE_GENERAL);
        mLooper.dispatchAll();

        verify(mLohsCallback).onHotspotFailed(ERROR_GENERIC);
    }

    /**
     * Verify that onFailed is called for registered LOHS callers on SAP_START_FAILURE_NO_CHANNEL.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApFailureNoChannel() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        registerLOHSRequestFull();

        changeLohsState(WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_DISABLED, SAP_START_FAILURE_NO_CHANNEL);

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotFailed(ERROR_NO_CHANNEL);
    }

    /**
     * Common setup for starting a LOHS.
     */
    private void setupLocalOnlyHotspot() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        registerLOHSRequestFull();

        changeLohsState(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR);
        mWifiServiceImpl.updateInterfaceIpState(mLohsInterfaceName, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStarted(any());
    }

    /**
     * Verify that onStopped is called for registered LOHS callers when a callback is
     * received with WIFI_AP_STATE_DISABLING and LOHS was active.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApDisabling() throws Exception {
        setupLocalOnlyHotspot();

        changeLohsState(WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR);

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStopped();
    }


    /**
     * Verify that onStopped is called for registered LOHS callers when a callback is
     * received with WIFI_AP_STATE_DISABLED and LOHS was enabled.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApDisabled() throws Exception {
        setupLocalOnlyHotspot();

        changeLohsState(WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR);

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStopped();
    }

    /**
     * Verify that no callbacks are called for registered LOHS callers when a callback is
     * received and the softap started.
     */
    @Test
    public void testRegisteredCallbacksNotTriggeredOnSoftApStart() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        registerLOHSRequestFull();

        changeLohsState(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR);

        mLooper.dispatchAll();
        verifyZeroInteractions(ignoreStubs(mLohsCallback));
    }

    /**
     * Verify that onStopped is called only once for registered LOHS callers when
     * callbacks are received with WIFI_AP_STATE_DISABLING and
     * WIFI_AP_STATE_DISABLED when LOHS was enabled.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnlyOnceWhenSoftApDisabling() throws Exception {
        setupLocalOnlyHotspot();

        changeLohsState(WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR);
        changeLohsState(WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR);

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStopped();
    }

    /**
     * Verify that onFailed is called only once for registered LOHS callers when
     * callbacks are received with WIFI_AP_STATE_FAILED twice.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnlyOnceWhenSoftApFailsTwice() throws Exception {
        setupLocalOnlyHotspot();

        changeLohsState(WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC);
        changeLohsState(WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC);

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotFailed(ERROR_GENERIC);
    }

    /**
     * Verify that onFailed is called for all registered LOHS callers when
     * callbacks are received with WIFI_AP_STATE_FAILED.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApFails() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        // make an additional request for this test
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        registerLOHSRequestFull();

        changeLohsState(WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC);
        changeLohsState(WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC);

        verify(mRequestInfo).sendHotspotFailedMessage(ERROR_GENERIC);
        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotFailed(ERROR_GENERIC);
    }

    /**
     * Verify that onStopped is called for all registered LOHS callers when
     * callbacks are received with WIFI_AP_STATE_DISABLED when LOHS was
     * active.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApStops() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mRequestInfo).sendHotspotStartedMessage(any());
        verify(mLohsCallback).onHotspotStarted(any());

        reset(mRequestInfo);
        clearInvocations(mLohsCallback);

        changeLohsState(WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR);
        changeLohsState(WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR);

        verify(mRequestInfo).sendHotspotStoppedMessage();
        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStopped();
    }

    /**
     * Verify that onFailed is called for all registered LOHS callers when
     * callbacks are received with WIFI_AP_STATE_DISABLED when LOHS was
     * not active.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApStopsLOHSNotActive() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        verifyApRegistration();

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);
        mWifiServiceImpl.registerLOHSForTest(TEST_PID2, mRequestInfo2);

        changeLohsState(WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR);
        changeLohsState(WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR);

        verify(mRequestInfo).sendHotspotFailedMessage(ERROR_GENERIC);
        verify(mRequestInfo2).sendHotspotFailedMessage(ERROR_GENERIC);
    }

    /**
     * Verify that if we do not have registered LOHS requestors and we receive an update that LOHS
     * is up and ready for use, we tell WifiController to tear it down.  This can happen if softap
     * mode fails to come up properly and we get an onFailed message for a tethering call and we
     * had registered callers for LOHS.
     */
    @Test
    public void testLOHSReadyWithoutRegisteredRequestsStopsSoftApMode() {
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
    }

    /**
     * Verify that all registered LOHS requestors are notified via a HOTSPOT_STARTED message that
     * the hotspot is up and ready to use.
     */
    @Test
    public void testRegisteredLocalOnlyHotspotRequestorsGetOnStartedCallbackWhenReady()
            throws Exception {
        registerLOHSRequestFull();

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mRequestInfo).sendHotspotStartedMessage(any(SoftApConfiguration.class));

        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStarted(notNull());
    }

    /**
     * Verify that if a LOHS is already active, a new call to register a request will trigger the
     * onStarted callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestAfterAlreadyStartedGetsOnStartedCallback()
            throws Exception {
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        changeLohsState(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        registerLOHSRequestFull();

        mLooper.dispatchAll();

        verify(mLohsCallback).onHotspotStarted(any());
    }

    /**
     * Verify that if a LOHS request is active and we receive an update with an ip mode
     * configuration error, callers are notified via the onFailed callback with the generic
     * error and are unregistered.
     */
    @Test
    public void testCallOnFailedLocalOnlyHotspotRequestWhenIpConfigFails() throws Exception {
        setupLocalOnlyHotspot();
        reset(mActiveModeWarden);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();

        verify(mLohsCallback).onHotspotFailed(ERROR_GENERIC);
        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);

        clearInvocations(mLohsCallback);

        // send HOTSPOT_FAILED message should only happen once since the requestor should be
        // unregistered
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();
        verifyZeroInteractions(ignoreStubs(mLohsCallback));
    }

    /**
     * Verify that softap mode is stopped for tethering if we receive an update with an ip mode
     * configuration error.
     */
    @Test
    public void testStopSoftApWhenIpConfigFails() throws Exception {
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();

        verify(mActiveModeWarden).stopSoftAp(IFACE_IP_MODE_TETHERED);
    }

    /**
     * Verify that if a LOHS request is active and tethering starts, callers are notified on the
     * incompatible mode and are unregistered.
     */
    @Test
    public void testCallOnFailedLocalOnlyHotspotRequestWhenTetheringStarts() throws Exception {
        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStarted(any());
        clearInvocations(mLohsCallback);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        verify(mLohsCallback).onHotspotFailed(ERROR_INCOMPATIBLE_MODE);

        // sendMessage should only happen once since the requestor should be unregistered
        clearInvocations(mLohsCallback);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        verifyZeroInteractions(ignoreStubs(mLohsCallback));
    }

    /**
     * Verify that if LOHS is disabled, a new call to register a request will not trigger the
     * onStopped callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestWhenStoppedDoesNotGetOnStoppedCallback()
            throws Exception {
        registerLOHSRequestFull();
        mLooper.dispatchAll();

        verifyZeroInteractions(ignoreStubs(mLohsCallback));
    }

    /**
     * Verify that if a LOHS was active and then stopped, a new call to register a request will
     * not trigger the onStarted callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestAfterStoppedNoOnStartedCallback()
            throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verifyApRegistration();

        // register a request so we don't drop the LOHS interface ip update
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);
        changeLohsState(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        registerLOHSRequestFull();
        mLooper.dispatchAll();

        verify(mLohsCallback).onHotspotStarted(any());

        clearInvocations(mLohsCallback);

        // now stop the hotspot
        changeLohsState(WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR);
        changeLohsState(WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR);
        mLooper.dispatchAll();
        verify(mLohsCallback).onHotspotStopped();

        clearInvocations(mLohsCallback);

        // now register a new caller - they should not get the onStarted callback
        ILocalOnlyHotspotCallback callback2 = mock(ILocalOnlyHotspotCallback.class);
        when(callback2.asBinder()).thenReturn(mock(IBinder.class));

        int result = mWifiServiceImpl.startLocalOnlyHotspot(
                callback2, TEST_PACKAGE_NAME, TEST_FEATURE_ID, null);
        assertEquals(LocalOnlyHotspotCallback.REQUEST_REGISTERED, result);
        mLooper.dispatchAll();

        verify(mLohsCallback, never()).onHotspotStarted(any());
    }

    /**
     * Verify that a call to startWatchLocalOnlyHotspot is only allowed from callers with the
     * signature only NETWORK_SETTINGS permission.
     *
     * This test is expecting the permission check to enforce the permission and throw a
     * SecurityException for callers without the permission.  This exception should be bubbled up to
     * the caller of startLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartWatchLocalOnlyHotspotNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        mWifiServiceImpl.startWatchLocalOnlyHotspot(mLohsCallback);
    }

    /**
     * Verify that the call to startWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * when called until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStartWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.startWatchLocalOnlyHotspot(mLohsCallback);
    }

    /**
     * Verify that a call to stopWatchLocalOnlyHotspot is only allowed from callers with the
     * signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testStopWatchLocalOnlyHotspotNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        mWifiServiceImpl.stopWatchLocalOnlyHotspot();
    }

    /**
     * Verify that the call to stopWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStopWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.stopWatchLocalOnlyHotspot();
    }

    /**
     * Verify that the call to addOrUpdateNetwork for installing Passpoint profile is redirected
     * to the Passpoint specific API addOrUpdatePasspointConfiguration.
     */
    @Test
    public void testAddPasspointProfileViaAddNetwork() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createPasspointNetwork();
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);

        PackageManager pm = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfoAsUser(any(), anyInt(), any())).thenReturn(mApplicationInfo);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(true);

        when(mPasspointManager.addOrUpdateProvider(
                any(PasspointConfiguration.class), anyInt(), eq(TEST_PACKAGE_NAME), eq(false),
                eq(true))).thenReturn(true);
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mPasspointManager).addOrUpdateProvider(
                any(PasspointConfiguration.class), anyInt(), eq(TEST_PACKAGE_NAME), eq(false),
                eq(true));
        reset(mPasspointManager);

        when(mPasspointManager.addOrUpdateProvider(
                any(PasspointConfiguration.class), anyInt(), eq(TEST_PACKAGE_NAME), anyBoolean(),
                anyBoolean())).thenReturn(false);
        mLooper.startAutoDispatch();
        assertEquals(-1, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mPasspointManager).addOrUpdateProvider(
                any(PasspointConfiguration.class), anyInt(), eq(TEST_PACKAGE_NAME), anyBoolean(),
                anyBoolean());
    }

    /**
     * Verify that the call to getAllMatchingPasspointProfilesForScanResults is not redirected to
     * specific API getAllMatchingPasspointProfilesForScanResults when the caller doesn't have
     * NETWORK_SETTINGS permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testGetAllMatchingPasspointProfilesForScanResultsWithoutPermissions() {
        mWifiServiceImpl.getAllMatchingPasspointProfilesForScanResults(new ArrayList<>());
    }

    /**
     * Verify that the call to getAllMatchingPasspointProfilesForScanResults is redirected to
     * specific API getAllMatchingPasspointProfilesForScanResults when the caller have
     * NETWORK_SETTINGS permissions and NETWORK_SETUP_WIZARD.
     */
    @Test
    public void testGetAllMatchingPasspointProfilesForScanResultsWithPermissions() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mLooper.startAutoDispatch();
        mWifiServiceImpl.getAllMatchingPasspointProfilesForScanResults(createScanResultList());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).getAllMatchingPasspointProfilesForScanResults(any());
    }

    /**
     * Verify that the call to getAllMatchingPasspointProfilesForScanResults is not redirected to
     * specific API getAllMatchingPasspointProfilesForScanResults when the caller provider invalid
     * ScanResult.
     */
    @Test
    public void testGetAllMatchingPasspointProfilesForScanResultsWithInvalidScanResult() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mLooper.startAutoDispatch();
        mWifiServiceImpl.getAllMatchingPasspointProfilesForScanResults(new ArrayList<>());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager, never()).getAllMatchingPasspointProfilesForScanResults(any());
    }

    /**
     * Verify that the call to getWifiConfigsForPasspointProfiles is not redirected to specific API
     * syncGetWifiConfigsForPasspointProfiles when the caller doesn't have NETWORK_SETTINGS
     * permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testGetWifiConfigsForPasspointProfilesWithoutPermissions() {
        mWifiServiceImpl.getWifiConfigsForPasspointProfiles(new ArrayList<>());
    }

    /**
     * Verify that the call to getMatchingOsuProviders is not redirected to specific API
     * syncGetMatchingOsuProviders when the caller doesn't have NETWORK_SETTINGS
     * permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testGetMatchingOsuProvidersWithoutPermissions() {
        mWifiServiceImpl.getMatchingOsuProviders(new ArrayList<>());
    }

    /**
     * Verify that the call to getMatchingOsuProviders is redirected to specific API
     * syncGetMatchingOsuProviders when the caller have NETWORK_SETTINGS
     * permissions and NETWORK_SETUP_WIZARD.
     */
    @Test
    public void testGetMatchingOsuProvidersWithPermissions() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mLooper.startAutoDispatch();
        mWifiServiceImpl.getMatchingOsuProviders(createScanResultList());
        mLooper.stopAutoDispatch();
        verify(mPasspointManager).getMatchingOsuProviders(any());
    }

    /**
     * Verify that the call to getMatchingOsuProviders is not redirected to specific API
     * syncGetMatchingOsuProviders when the caller provider invalid ScanResult
     */
    @Test
    public void testGetMatchingOsuProvidersWithInvalidScanResult() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl.getMatchingOsuProviders(new ArrayList<>());
        mLooper.dispatchAll();
        verify(mPasspointManager, never()).getMatchingOsuProviders(any());
    }

    /**
     * Verify that the call to getMatchingPasspointConfigsForOsuProviders is not redirected to
     * specific API syncGetMatchingPasspointConfigsForOsuProviders when the caller doesn't have
     * NETWORK_SETTINGS permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testGetMatchingPasspointConfigsForOsuProvidersWithoutPermissions() {
        mWifiServiceImpl.getMatchingPasspointConfigsForOsuProviders(new ArrayList<>());
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller has the right permissions.
     */
    @Test
    public void testStartSubscriptionProvisioningWithPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);

        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, mProvisioningCallback);
        verify(mClientModeManager).syncStartSubscriptionProvisioning(anyInt(),
                eq(mOsuProvider), eq(mProvisioningCallback));
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller provides invalid arguments
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStartSubscriptionProvisioningWithInvalidProvider() throws Exception {
        mWifiServiceImpl.startSubscriptionProvisioning(null, mProvisioningCallback);
    }


    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller provides invalid callback
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStartSubscriptionProvisioningWithInvalidCallback() throws Exception {
        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, null);
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller doesn't have NETWORK_SETTINGS
     * permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testStartSubscriptionProvisioningWithoutPermissions() throws Exception {
        when(mContext.checkCallingOrSelfPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS))).thenReturn(
                PackageManager.PERMISSION_DENIED);
        when(mContext.checkSelfPermission(
                eq(android.Manifest.permission.NETWORK_SETUP_WIZARD))).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, mProvisioningCallback);
    }

    /**
     * Verify the call to getPasspointConfigurations when the caller doesn't have
     * NETWORK_SETTINGS and NETWORK_SETUP_WIZARD permissions.
     */
    @Test
    public void testGetPasspointConfigurationsWithOutPrivilegedPermissions() {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.checkNetworkSetupWizardPermission(anyInt())).thenReturn(false);

        mLooper.startAutoDispatch();
        mWifiServiceImpl.getPasspointConfigurations(TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).getProviderConfigs(Binder.getCallingUid(), false);
    }

    /**
     * Verify that the call to getPasspointConfigurations when the caller does have
     * NETWORK_SETTINGS permission.
     */
    @Test
    public void testGetPasspointConfigurationsWithPrivilegedPermissions() {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);

        mLooper.startAutoDispatch();
        mWifiServiceImpl.getPasspointConfigurations(TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).getProviderConfigs(Binder.getCallingUid(), true);
    }

    /**
     * Verify that GetPasspointConfigurations will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager}.
     */
    @Test
    public void testGetPasspointConfigurations() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);

        // Setup expected configs.
        List<PasspointConfiguration> expectedConfigs = new ArrayList<>();
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);
        expectedConfigs.add(config);

        when(mPasspointManager.getProviderConfigs(anyInt(), anyBoolean()))
                .thenReturn(expectedConfigs);
        mLooper.startAutoDispatch();
        assertEquals(expectedConfigs, mWifiServiceImpl.getPasspointConfigurations(TEST_PACKAGE));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        reset(mPasspointManager);

        when(mPasspointManager.getProviderConfigs(anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<PasspointConfiguration>());
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.getPasspointConfigurations(TEST_PACKAGE).isEmpty());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Verify the call to removePasspointConfigurations when the caller doesn't have
     * NETWORK_SETTINGS and NETWORK_CARRIER_PROVISIONING permissions.
     */
    @Test
    public void testRemovePasspointConfigurationWithOutPrivilegedPermissions() {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(anyInt())).thenReturn(
                false);

        mLooper.startAutoDispatch();
        mWifiServiceImpl.removePasspointConfiguration(TEST_FQDN, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).removeProvider(Binder.getCallingUid(), false, null,
                TEST_FQDN);
    }

    /**
     * Verify the call to removePasspointConfigurations when the caller does have
     * NETWORK_CARRIER_PROVISIONING permission.
     */
    @Test
    public void testRemovePasspointConfigurationWithPrivilegedPermissions() {
        when(mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(anyInt())).thenReturn(
                true);

        mLooper.startAutoDispatch();
        mWifiServiceImpl.removePasspointConfiguration(TEST_FQDN, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).removeProvider(Binder.getCallingUid(), true, null,
                TEST_FQDN);
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#restoreBackupData(byte[])} is only allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRestoreBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.restoreBackupData(null);
        verify(mWifiBackupRestore, never()).retrieveConfigurationsFromBackupData(any(byte[].class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#restoreSupplicantBackupData(byte[], byte[])} is
     * only allowed from callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRestoreSupplicantBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.restoreSupplicantBackupData(null, null);
        verify(mWifiBackupRestore, never()).retrieveConfigurationsFromSupplicantBackupData(
                any(byte[].class), any(byte[].class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#retrieveBackupData()} is only allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRetrieveBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.retrieveBackupData();
        verify(mWifiBackupRestore, never()).retrieveBackupDataFromConfigurations(any(List.class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#restoreSoftApBackupData(byte[])}
     * is only allowed from callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRestoreSoftApBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.restoreSoftApBackupData(null);
        verify(mSoftApBackupRestore, never())
                .retrieveSoftApConfigurationFromBackupData(any(byte[].class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#retrieveSoftApBackupData()} is only allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRetrieveSoftApBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.retrieveSoftApBackupData();
        verify(mSoftApBackupRestore, never())
                .retrieveBackupDataFromSoftApConfiguration(any(SoftApConfiguration.class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#enableVerboseLogging(int)} is allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test
    public void testEnableVerboseLoggingWithNetworkSettingsPermission() {
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        // Vebose logging is enabled first in the constructor for WifiServiceImpl, so reset
        // before invocation.
        reset(mClientModeManager);
        mWifiServiceImpl.enableVerboseLogging(1);
        verify(mWifiSettingsConfigStore).put(WIFI_VERBOSE_LOGGING_ENABLED, true);
        verify(mActiveModeWarden).enableVerboseLogging(anyBoolean());
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#enableVerboseLogging(int)} is not allowed from
     * callers without the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testEnableVerboseLoggingWithNoNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        // Vebose logging is enabled first in the constructor for WifiServiceImpl, so reset
        // before invocation.
        reset(mClientModeManager);
        mWifiServiceImpl.enableVerboseLogging(1);
        verify(mWifiSettingsConfigStore, never()).put(
                WIFI_VERBOSE_LOGGING_ENABLED, anyBoolean());
        verify(mActiveModeWarden, never()).enableVerboseLogging(anyBoolean());
    }

    /**
     * Verify that the CONNECT_NETWORK message received from an app without
     * one of the privileged permission is rejected with a security exception.
     */
    @Test
    public void testConnectNetworkWithoutPrivilegedPermission() throws Exception {
        try {
            mWifiServiceImpl.connect(mock(WifiConfiguration.class), TEST_NETWORK_ID,
                    mock(IActionListener.class));
            fail();
        } catch (SecurityException e) {
            mLooper.dispatchAll();
            verify(mConnectHelper, never()).connectToNetwork(any(), any(), anyInt());
        }
    }

    /**
     * Verify that the FORGET_NETWORK message received from an app without
     * one of the privileged permission is rejected with a security exception.
     */
    @Test
    public void testForgetNetworkWithoutPrivilegedPermission() throws Exception {
        try {
            mWifiServiceImpl.forget(TEST_NETWORK_ID, mock(IActionListener.class));
            fail();
        } catch (SecurityException e) {
            mLooper.dispatchAll();
            verify(mWifiConfigManager, never()).removeNetwork(anyInt(), anyInt(), any());
        }
    }

    /**
     * Verify that the SAVE_NETWORK message received from an app without
     * one of the privileged permission is rejected with a security exception.
     */
    @Test
    public void testSaveNetworkWithoutPrivilegedPermission() throws Exception {
        try {
            mWifiServiceImpl.save(mock(WifiConfiguration.class), mock(IActionListener.class));
            fail();
        } catch (SecurityException e) {
            mLooper.dispatchAll();
            verify(mWifiConfigManager, never()).updateBeforeSaveNetwork(any(), anyInt());
        }
    }

    /**
     * Verify that the CONNECT_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeManager.
     */
    @Test
    public void testConnectNetworkWithPrivilegedPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
            anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = TEST_SSID;
        mWifiServiceImpl.connect(config, TEST_NETWORK_ID, mock(IActionListener.class));
        mLooper.dispatchAll();
        verify(mWifiConfigManager).addOrUpdateNetwork(eq(config), anyInt());
        verify(mConnectHelper).connectToNetwork(any(NetworkUpdateResult.class),
                any(ActionListenerWrapper.class), anyInt());
        verify(mWifiMetrics).logUserActionEvent(eq(UserActionEvent.EVENT_ADD_OR_UPDATE_NETWORK),
                anyInt());
    }

    @Test
    public void connectToNewNetwork_success() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);
        NetworkUpdateResult result = new NetworkUpdateResult(TEST_NETWORK_ID);
        when(mWifiConfigManager.addOrUpdateNetwork(eq(mWifiConfig), anyInt()))
                .thenReturn(result);

        mWifiServiceImpl.connect(mWifiConfig, WifiConfiguration.INVALID_NETWORK_ID,
                mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).addOrUpdateNetwork(eq(mWifiConfig), anyInt());

        verify(mConnectHelper).connectToNetwork(eq(result), any(), anyInt());
        verify(mContextAsUser).sendBroadcastWithMultiplePermissions(
                mIntentCaptor.capture(),
                aryEq(new String[]{
                        android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                }));

        Intent intent = mIntentCaptor.getValue();
        assertThat(intent.getAction()).isEqualTo(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        assertThat(intent.getStringExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID))
                .isEqualTo(TEST_SSID);
        assertThat(intent.getIntExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE, -1))
                .isEqualTo(WifiManager.WIFI_CREDENTIAL_SAVED);
    }

    @Test
    public void connectToNewNetwork_failure() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiConfigManager.addOrUpdateNetwork(eq(mWifiConfig), anyInt()))
                .thenReturn(NetworkUpdateResult.makeFailed());
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mWifiServiceImpl.connect(mWifiConfig, WifiConfiguration.INVALID_NETWORK_ID,
                mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).addOrUpdateNetwork(eq(mWifiConfig), anyInt());

        verify(mClientModeManager, never()).connectNetwork(any(), any(), anyInt());
        verify(mContextAsUser, never()).sendBroadcastWithMultiplePermissions(any(), any());
        verify(mActionListener).onFailure(WifiManager.ERROR);
        verify(mActionListener, never()).onSuccess();
    }

    @Test
    public void connectToExistingNetwork() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mWifiServiceImpl.connect(null, TEST_NETWORK_ID, mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager, never()).addOrUpdateNetwork(any(), anyInt());

        verify(mConnectHelper).connectToNetwork(
                eq(new NetworkUpdateResult(TEST_NETWORK_ID)), any(), anyInt());
        verify(mContextAsUser, never()).sendBroadcastWithMultiplePermissions(any(), any());
        verify(mWifiMetrics).logUserActionEvent(eq(UserActionEvent.EVENT_MANUAL_CONNECT), anyInt());
    }

    /**
     * Verify that the SAVE_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeManager.
     */
    @Test
    public void testSaveNetworkWithPrivilegedPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
            anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiConfigManager.updateBeforeSaveNetwork(any(), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        mWifiServiceImpl.save(mock(WifiConfiguration.class), mock(IActionListener.class));
        mLooper.dispatchAll();
        verify(mWifiConfigManager).updateBeforeSaveNetwork(any(WifiConfiguration.class), anyInt());
        verify(mWifiMetrics).logUserActionEvent(eq(UserActionEvent.EVENT_ADD_OR_UPDATE_NETWORK),
                anyInt());
    }

    @Test
    public void saveNetwork_success() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        NetworkUpdateResult result = new NetworkUpdateResult(TEST_NETWORK_ID);
        when(mWifiConfigManager.updateBeforeSaveNetwork(eq(mWifiConfig), anyInt()))
                .thenReturn(result);

        mWifiServiceImpl.save(mWifiConfig, mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).updateBeforeSaveNetwork(eq(mWifiConfig), anyInt());

        verify(mClientModeManager).saveNetwork(eq(result), any(), anyInt());
        verify(mContextAsUser).sendBroadcastWithMultiplePermissions(
                mIntentCaptor.capture(),
                aryEq(new String[]{
                        android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                }));

        Intent intent = mIntentCaptor.getValue();
        assertThat(intent.getAction()).isEqualTo(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        assertThat(intent.getStringExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID))
                .isEqualTo(TEST_SSID);
        assertThat(intent.getIntExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE, -1))
                .isEqualTo(WifiManager.WIFI_CREDENTIAL_SAVED);
    }

    @Test
    public void saveNetwork_failure() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiConfigManager.updateBeforeSaveNetwork(eq(mWifiConfig), anyInt()))
                .thenReturn(NetworkUpdateResult.makeFailed());
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mWifiServiceImpl.save(mWifiConfig, mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).updateBeforeSaveNetwork(eq(mWifiConfig), anyInt());

        verify(mClientModeManager, never()).saveNetwork(any(), any(), anyInt());
        verify(mContext, never()).sendBroadcastWithMultiplePermissions(any(), any());
        verify(mActionListener).onFailure(WifiManager.ERROR);
        verify(mActionListener, never()).onSuccess();
    }

    /**
     * Verify that the FORGET_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeManager.
     */
    @Test
    public void testForgetNetworkWithPrivilegedPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
            anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        when(mWifiConfigManager.removeNetwork(anyInt(), anyInt(), any())).thenReturn(true);
        mWifiServiceImpl.forget(TEST_NETWORK_ID, mock(IActionListener.class));

        InOrder inOrder = inOrder(mWifiConfigManager, mWifiMetrics);
        inOrder.verify(mWifiMetrics).logUserActionEvent(
                UserActionEvent.EVENT_FORGET_WIFI, TEST_NETWORK_ID);

        mLooper.dispatchAll();
        inOrder.verify(mWifiConfigManager).removeNetwork(anyInt(), anyInt(), any());
    }

    @Test
    public void forgetNetwork_success() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(mWifiConfig);
        when(mWifiConfigManager.removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any()))
                .thenReturn(true);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mWifiServiceImpl.forget(TEST_NETWORK_ID, mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any());
        verify(mActionListener).onSuccess();
        verify(mActionListener, never()).onFailure(WifiManager.ERROR);

        verify(mContextAsUser).sendBroadcastWithMultiplePermissions(
                mIntentCaptor.capture(),
                aryEq(new String[]{
                        android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                }));

        Intent intent = mIntentCaptor.getValue();
        assertThat(intent.getAction()).isEqualTo(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        assertThat(intent.getStringExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID))
                .isEqualTo(TEST_SSID);
        assertThat(intent.getIntExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE, -1))
                .isEqualTo(WifiManager.WIFI_CREDENTIAL_FORGOT);
    }

    @Test
    public void forgetNetwork_successNoLocation_dontBroadcastSsid() throws Exception {
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(false);

        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(mWifiConfig);
        when(mWifiConfigManager.removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any()))
                .thenReturn(true);

        mWifiServiceImpl.forget(TEST_NETWORK_ID, mActionListener);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any());
        verify(mActionListener).onSuccess();
        verify(mActionListener, never()).onFailure(WifiManager.ERROR);

        verify(mContextAsUser).sendBroadcastWithMultiplePermissions(
                mIntentCaptor.capture(),
                aryEq(new String[]{
                        android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                }));

        Intent intent = mIntentCaptor.getValue();
        assertThat(intent.getAction()).isEqualTo(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        // SSID is null if location is disabled
        assertThat(intent.getStringExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID)).isNull();
        assertThat(intent.getIntExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE, -1))
                .isEqualTo(WifiManager.WIFI_CREDENTIAL_FORGOT);
    }

    @Test
    public void forgetNetwork_failed() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiConfigManager.removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any()))
                .thenReturn(false);
        when(mWifiPermissionsUtil.isLocationModeEnabled()).thenReturn(true);

        mWifiServiceImpl.forget(TEST_NETWORK_ID, mActionListener);
        mLooper.dispatchAll();

        verify(mActionListener, never()).onSuccess();
        verify(mActionListener).onFailure(WifiManager.ERROR);
        verify(mWifiConfigManager).removeNetwork(eq(TEST_NETWORK_ID), anyInt(), any());
        verify(mContextAsUser, never()).sendBroadcastWithMultiplePermissions(any(), any());
    }

    /**
     * Tests the scenario when a scan request arrives while the device is idle. In this case
     * the scan is done when idle mode ends.
     */
    @Test
    public void testHandleDelayedScanAfterIdleMode() throws Exception {
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mWifiInjector.getPasspointProvisionerHandlerThread())
                .thenReturn(mock(HandlerThread.class));
        mLooper.startAutoDispatch();
        mWifiServiceImpl.checkAndStartWifi();
        mWifiServiceImpl.handleBootCompleted();
        mLooper.stopAutoDispatch();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IdleModeIntentMatcher()));

        // Tell the wifi service that the device became idle.
        when(mPowerManager.isDeviceIdleMode()).thenReturn(true);
        TestUtil.sendIdleModeChanged(mBroadcastReceiverCaptor.getValue(), mContext);

        // Send a scan request while the device is idle.
        mWifiThreadRunner.prepareForAutoDispatch();
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatch();
        // No scans must be made yet as the device is idle.
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);

        // Tell the wifi service that idle mode ended.
        when(mPowerManager.isDeviceIdleMode()).thenReturn(false);
        mWifiThreadRunner.prepareForAutoDispatch();
        mLooper.startAutoDispatch();
        TestUtil.sendIdleModeChanged(mBroadcastReceiverCaptor.getValue(), mContext);
        mLooper.stopAutoDispatch();

        // Must scan now.
        verify(mScanRequestProxy).startScan(Process.myUid(), TEST_PACKAGE_NAME);
        // The app ops check is executed with this package's identity (not the identity of the
        // original remote caller who requested the scan while idle).
        verify(mAppOpsManager).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);

        // Send another scan request. The device is not idle anymore, so it must be executed
        // immediately.
        mWifiThreadRunner.prepareForAutoDispatch();
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME, TEST_FEATURE_ID));
        mLooper.stopAutoDispatch();
        verify(mScanRequestProxy).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Verify that if the caller has NETWORK_SETTINGS permission, then it doesn't need
     * CHANGE_WIFI_STATE permission.
     */
    @Test
    public void testDisconnectWithNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        doThrow(new SecurityException()).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        assertTrue(mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME));
        verify(mClientModeManager).disconnect();
    }

    /**
     * Verify that if the caller doesn't have NETWORK_SETTINGS permission, it could still
     * get access with the CHANGE_WIFI_STATE permission.
     */
    @Test
    public void testDisconnectWithChangeWifiStatePerm() throws Exception {
        assertFalse(mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME));
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeManager, never()).disconnect();
    }

    /**
     * Verify that the operation fails if the caller has neither NETWORK_SETTINGS or
     * CHANGE_WIFI_STATE permissions.
     */
    @Test
    public void testDisconnectRejected() throws Exception {
        doThrow(new SecurityException()).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        try {
            mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME);
            fail();
        } catch (SecurityException e) {

        }
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeManager, never()).disconnect();
    }

    @Test
    public void testPackageFullyRemovedBroadcastHandling() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_CHANGED)));
        int uid = TEST_UID;
        String packageName = TEST_PACKAGE_NAME;
        doThrow(new PackageManager.NameNotFoundException()).when(mPackageManager)
                .getApplicationInfo(TEST_PACKAGE_NAME, 0);
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        ArgumentCaptor<ApplicationInfo> aiCaptor = ArgumentCaptor.forClass(ApplicationInfo.class);
        verify(mWifiConfigManager).removeNetworksForApp(aiCaptor.capture());
        assertNotNull(aiCaptor.getValue());
        assertEquals(uid, aiCaptor.getValue().uid);
        assertEquals(packageName, aiCaptor.getValue().packageName);

        verify(mScanRequestProxy).clearScanRequestTimestampsForApp(packageName, uid);
        verify(mWifiNetworkSuggestionsManager).removeApp(packageName);
        verify(mWifiNetworkFactory).removeUserApprovedAccessPointsForApp(packageName);
        verify(mPasspointManager).removePasspointProviderWithPackage(packageName);
    }

    @Test
    public void testPackageRemovedBroadcastHandling() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_CHANGED)));
        int uid = TEST_UID;
        String packageName = TEST_PACKAGE_NAME;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        ArgumentCaptor<ApplicationInfo> aiCaptor = ArgumentCaptor.forClass(ApplicationInfo.class);
        verify(mWifiConfigManager).removeNetworksForApp(aiCaptor.capture());
        assertNotNull(aiCaptor.getValue());
        assertEquals(uid, aiCaptor.getValue().uid);
        assertEquals(packageName, aiCaptor.getValue().packageName);

        verify(mScanRequestProxy).clearScanRequestTimestampsForApp(packageName, uid);
        verify(mWifiNetworkSuggestionsManager).removeApp(packageName);
        verify(mWifiNetworkFactory).removeUserApprovedAccessPointsForApp(packageName);
        verify(mPasspointManager).removePasspointProviderWithPackage(packageName);
    }

    @Test
    public void testPackageDisableBroadcastHandling() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_CHANGED)));
        int uid = TEST_UID;
        String packageName = TEST_PACKAGE_NAME;
        mPackageInfo.applicationInfo = mApplicationInfo;
        mApplicationInfo.enabled = false;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        ArgumentCaptor<ApplicationInfo> aiCaptor = ArgumentCaptor.forClass(ApplicationInfo.class);
        verify(mWifiConfigManager).removeNetworksForApp(aiCaptor.capture());
        assertNotNull(aiCaptor.getValue());
        assertEquals(uid, aiCaptor.getValue().uid);
        assertEquals(packageName, aiCaptor.getValue().packageName);

        verify(mScanRequestProxy).clearScanRequestTimestampsForApp(packageName, uid);
        verify(mWifiNetworkSuggestionsManager).removeApp(packageName);
        verify(mWifiNetworkFactory).removeUserApprovedAccessPointsForApp(packageName);
        verify(mPasspointManager).removePasspointProviderWithPackage(packageName);
    }

    @Test
    public void testPackageRemovedBroadcastHandlingWithNoUid() {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)));

        String packageName = TEST_PACKAGE_NAME;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mWifiConfigManager, never()).removeNetworksForApp(any());

        mLooper.dispatchAll();
        verify(mScanRequestProxy, never()).clearScanRequestTimestampsForApp(anyString(), anyInt());
        verify(mWifiNetworkSuggestionsManager, never()).removeApp(anyString());
        verify(mWifiNetworkFactory, never()).removeUserApprovedAccessPointsForApp(anyString());
        verify(mPasspointManager, never()).removePasspointProviderWithPackage(anyString());
    }

    @Test
    public void testPackageRemovedBroadcastHandlingWithNoPackageName() {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)));

        int uid = TEST_UID;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mWifiConfigManager, never()).removeNetworksForApp(any());

        mLooper.dispatchAll();
        verify(mScanRequestProxy, never()).clearScanRequestTimestampsForApp(anyString(), anyInt());
        verify(mWifiNetworkSuggestionsManager, never()).removeApp(anyString());
        verify(mWifiNetworkFactory, never()).removeUserApprovedAccessPointsForApp(anyString());
        verify(mPasspointManager, never()).removePasspointProviderWithPackage(anyString());
    }

    @Test
    public void testUserRemovedBroadcastHandling() {
        when(mWifiInjector.getPasspointProvisionerHandlerThread())
                .thenReturn(mock(HandlerThread.class));
        mLooper.startAutoDispatch();
        mWifiServiceImpl.checkAndStartWifi();
        mWifiServiceImpl.handleBootCompleted();
        mLooper.stopAutoDispatch();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_USER_REMOVED)));

        UserHandle userHandle = UserHandle.of(TEST_USER_HANDLE);
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).removeNetworksForUser(userHandle.getIdentifier());
    }

    @Test
    public void testBluetoothBroadcastHandling() {
        when(mWifiInjector.getPasspointProvisionerHandlerThread())
                .thenReturn(mock(HandlerThread.class));
        mLooper.startAutoDispatch();
        mWifiServiceImpl.checkAndStartWifi();
        mWifiServiceImpl.handleBootCompleted();
        mLooper.stopAutoDispatch();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                                && filter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED)));

        {
            Intent intent = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.STATE_DISCONNECTED);
            mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
            mLooper.dispatchAll();

            verify(mWifiGlobals).setBluetoothConnected(false);
            for (ClientModeManager cmm : mClientModeManagers) {
                verify(cmm).onBluetoothConnectionStateChanged();
            }
        }

        {
            Intent intent = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.STATE_CONNECTED);
            mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
            mLooper.dispatchAll();

            verify(mWifiGlobals).setBluetoothConnected(true);
            for (ClientModeManager cmm : mClientModeManagers) {
                verify(cmm, times(2)).onBluetoothConnectionStateChanged();
            }
        }

        {
            Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
            mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
            mLooper.dispatchAll();

            verify(mWifiGlobals).setBluetoothEnabled(false);
            for (ClientModeManager cmm : mClientModeManagers) {
                verify(cmm, times(3)).onBluetoothConnectionStateChanged();
            }
        }

        {
            Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
            mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
            mLooper.dispatchAll();

            verify(mWifiGlobals).setBluetoothEnabled(true);
            for (ClientModeManager cmm : mClientModeManagers) {
                verify(cmm, times(4)).onBluetoothConnectionStateChanged();
            }
        }
    }

    @Test
    public void testUserRemovedBroadcastHandlingWithWrongIntentAction() {
        when(mWifiInjector.getPasspointProvisionerHandlerThread())
                .thenReturn(mock(HandlerThread.class));
        mLooper.startAutoDispatch();
        mWifiServiceImpl.checkAndStartWifi();
        mWifiServiceImpl.handleBootCompleted();
        mLooper.stopAutoDispatch();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_USER_REMOVED)));

        UserHandle userHandle = UserHandle.of(TEST_USER_HANDLE);
        // Send the broadcast with wrong action
        Intent intent = new Intent(Intent.ACTION_USER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mWifiConfigManager, never()).removeNetworksForUser(anyInt());
    }

    private class IdleModeIntentMatcher implements ArgumentMatcher<IntentFilter> {
        @Override
        public boolean matches(IntentFilter filter) {
            return filter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        }
    }

    /**
     * Verifies that enforceChangePermission(String package) is called and the caller doesn't
     * have NETWORK_SETTINGS permission
     */
    private void verifyCheckChangePermission(String callingPackageName) {
        verify(mContext, atLeastOnce())
                .checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        anyInt(), anyInt());
        verify(mContext, atLeastOnce()).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        verify(mAppOpsManager, atLeastOnce()).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), callingPackageName);
    }

    private WifiConfiguration createValidWifiApConfiguration() {
        WifiConfiguration apConfig = new WifiConfiguration();
        apConfig.SSID = "TestAp";
        apConfig.preSharedKey = "thisIsABadPassword";
        apConfig.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        apConfig.apBand = WifiConfiguration.AP_BAND_2GHZ;

        return apConfig;
    }

    private SoftApConfiguration createValidSoftApConfiguration() {
        return new SoftApConfiguration.Builder()
                .setSsid("TestAp")
                .setPassphrase("thisIsABadPassword", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_2GHZ)
                .build();
    }

    /**
     * Verifies that sim state change does not set or reset the country code
     */
    @Test
    public void testSimStateChangeDoesNotResetCountryCode() {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED)));

        int userHandle = TEST_USER_HANDLE;
        // Send the broadcast
        Intent intent = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        verifyNoMoreInteractions(mWifiCountryCode);
    }

    /**
     * Verifies that sim state change does not set or reset the country code
     */
    @Test
    public void testSimStateChangeDoesNotResetCountryCodeForRebroadcastedIntent() {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED)));

        int userHandle = TEST_USER_HANDLE;
        // Send the broadcast
        Intent intent = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, Intent.SIM_STATE_ABSENT);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        verifyNoMoreInteractions(mWifiCountryCode);
    }

    /**
     * Verify removing sim will also remove an ephemeral Passpoint Provider. And reset carrier
     * privileged suggestor apps.
     */
    @Test
    public void testResetSimNetworkWhenRemovingSim() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED)));

        Intent intent = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_ABSENT);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).resetSimNetworks();
        verify(mSimRequiredNotifier, never()).dismissSimRequiredNotification();
        verify(mWifiNetworkSuggestionsManager).resetCarrierPrivilegedApps();
        verify(mWifiConfigManager, never()).removeAllEphemeralOrPasspointConfiguredNetworks();
    }

    /**
     * Verify inserting sim will reset carrier privileged suggestor apps.
     * and remove any previous notifications due to sim removal
     */
    @Test
    public void testResetCarrierPrivilegedAppsWhenInsertingSim() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED)));

        Intent intent = new Intent(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_LOADED);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        verify(mWifiConfigManager, never()).resetSimNetworks();
        verify(mSimRequiredNotifier).dismissSimRequiredNotification();
        verify(mWifiNetworkSuggestionsManager).resetCarrierPrivilegedApps();
        verify(mWifiConfigManager, never()).removeAllEphemeralOrPasspointConfiguredNetworks();
    }

    @Test
    public void testResetSimNetworkWhenDefaultDataSimChanged() throws Exception {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(
                                TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)));

        Intent intent = new Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intent.putExtra("subscription", 1);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).resetSimNetworks();
        verify(mSimRequiredNotifier, never()).dismissSimRequiredNotification();
        verify(mWifiNetworkSuggestionsManager).resetCarrierPrivilegedApps();
        verify(mWifiConfigManager).removeEphemeralCarrierNetworks();
    }

    /**
     * Verify that a call to registerTrafficStateCallback throws a SecurityException if the caller
     * does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void registerTrafficStateCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.registerTrafficStateCallback(mAppBinder, mTrafficStateCallback,
                    TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to registerTrafficStateCallback throws an IllegalArgumentException if the
     * parameters are not provided.
     */
    @Test
    public void registerTrafficStateCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.registerTrafficStateCallback(
                    mAppBinder, null, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterTrafficStateCallback throws a SecurityException if the caller
     * does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void unregisterTrafficStateCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.unregisterTrafficStateCallback(TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that registerTrafficStateCallback adds callback to {@link WifiTrafficPoller}.
     */
    @Test
    public void registerTrafficStateCallbackAndVerify() throws Exception {
        mWifiServiceImpl.registerTrafficStateCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mWifiTrafficPoller).addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that unregisterTrafficStateCallback removes callback from {@link WifiTrafficPoller}.
     */
    @Test
    public void unregisterTrafficStateCallbackAndVerify() throws Exception {
        mWifiServiceImpl.unregisterTrafficStateCallback(0);
        mLooper.dispatchAll();
        verify(mWifiTrafficPoller).removeCallback(0);
    }

    /**
     * Verify that a call to registerNetworkRequestMatchCallback throws a SecurityException if the
     * caller does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void registerNetworkRequestMatchCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.registerNetworkRequestMatchCallback(mAppBinder,
                    mNetworkRequestMatchCallback,
                    TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to registerNetworkRequestMatchCallback throws an IllegalArgumentException
     * if the parameters are not provided.
     */
    @Test
    public void
            registerNetworkRequestMatchCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.registerNetworkRequestMatchCallback(
                    mAppBinder, null, TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterNetworkRequestMatchCallback throws a SecurityException if the
     * caller does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void unregisterNetworkRequestMatchCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.unregisterNetworkRequestMatchCallback(
                    TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that registerNetworkRequestMatchCallback adds callback to
     * {@link ClientModeManager}.
     */
    @Test
    public void registerNetworkRequestMatchCallbackAndVerify() throws Exception {
        mWifiServiceImpl.registerNetworkRequestMatchCallback(
                mAppBinder, mNetworkRequestMatchCallback,
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mWifiNetworkFactory).addCallback(
                mAppBinder, mNetworkRequestMatchCallback,
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that unregisterNetworkRequestMatchCallback removes callback from
     * {@link ClientModeManager}.
     */
    @Test
    public void unregisterNetworkRequestMatchCallbackAndVerify() throws Exception {
        mWifiServiceImpl.unregisterNetworkRequestMatchCallback(
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mWifiNetworkFactory).removeCallback(
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that Wifi configuration and Passpoint configuration are removed in factoryReset.
     */
    @Test
    public void testFactoryReset() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        final String fqdn = "example.com";
        WifiConfiguration network = WifiConfigurationTestUtil.createOpenNetwork();
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm("example.com");
        config.setCredential(credential);

        when(mWifiConfigManager.getSavedNetworks(anyInt()))
                .thenReturn(Arrays.asList(network));
        when(mPasspointManager.getProviderConfigs(anyInt(), anyBoolean()))
                .thenReturn(Arrays.asList(config));

        mLooper.startAutoDispatch();
        mWifiServiceImpl.factoryReset(TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        // Let the final post inside the |factoryReset| method run to completion.
        mLooper.dispatchAll();

        verify(mWifiConfigManager).removeNetwork(
                network.networkId, Binder.getCallingUid(), TEST_PACKAGE_NAME);
        verify(mPasspointManager).removeProvider(anyInt(), anyBoolean(), eq(config.getUniqueId()),
                isNull());
        verify(mPasspointManager).clearAnqpRequestsAndFlushCache();
        verify(mWifiConfigManager).clearUserTemporarilyDisabledList();
        verify(mWifiConfigManager).removeAllEphemeralOrPasspointConfiguredNetworks();
        verify(mWifiNetworkFactory).clear();
        verify(mWifiNetworkSuggestionsManager).clear();
        verify(mWifiScoreCard).clear();
        verify(mWifiHealthMonitor).clear();
        verify(mPasspointManager).getProviderConfigs(anyInt(), anyBoolean());
    }

    /**
     * Verify that a call to factoryReset throws a SecurityException if the caller does not have
     * the NETWORK_SETTINGS permission.
     */
    @Test
    public void testFactoryResetWithoutNetworkSettingsPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.factoryReset(TEST_PACKAGE_NAME);
            fail();
        } catch (SecurityException e) {
        }
        verify(mWifiConfigManager, never()).getSavedNetworks(anyInt());
        verify(mPasspointManager, never()).getProviderConfigs(anyInt(), anyBoolean());
    }

    /**
     * Verify that add or update networks is not allowed for apps targeting Q SDK.
     */
    @Test
    public void testAddOrUpdateNetworkIsNotAllowedForAppsTargetingQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(-1, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiConfigManager, never()).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics, never()).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for apps targeting below Q SDK.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForAppsTargetingBelowQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for settings app.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForSettingsApp() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        // Ensure that we don't check for change permission.
        verify(mContext, never()).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        verify(mAppOpsManager, never()).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for system apps.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForSystemApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(true);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for apps holding system alert permission.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForAppsWithSystemAlertPermission() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        when(mWifiPermissionsUtil.checkSystemAlertWindowPermission(
                Process.myUid(), TEST_PACKAGE_NAME)).thenReturn(true);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiPermissionsUtil).checkSystemAlertWindowPermission(anyInt(), anyString());
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for DeviceOwner app.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForDOApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isDeviceOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that add or update networks is allowed for ProfileOwner app.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForPOApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isProfileOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);
        when(mWifiConfigManager.addOrUpdateNetwork(any(),  anyInt(), any())).thenReturn(
                new NetworkUpdateResult(0));

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        mLooper.startAutoDispatch();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(),  anyInt(), any());
        verify(mWifiMetrics).incrementNumAddOrUpdateNetworkCalls();
    }

    /**
     * Verify that enableNetwork is allowed for privileged Apps
     */
    @Test
    public void testEnableNetworkWithDisableOthersAllowedForPrivilegedApps() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);

        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(NetworkUpdateResult result, ActionListenerWrapper callback,
                    int callingUid) {
                callback.sendSuccess(); // return success
            }
        }).when(mConnectHelper).connectToNetwork(
                eq(new NetworkUpdateResult(TEST_NETWORK_ID)), any(), anyInt());

        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.enableNetwork(TEST_NETWORK_ID, true, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatch();

        verify(mConnectHelper).connectToNetwork(
                eq(new NetworkUpdateResult(TEST_NETWORK_ID)), any(), anyInt());
        verify(mWifiMetrics).incrementNumEnableNetworkCalls();
    }

    /**
     * Verify that enableNetwork (with disableOthers=true) is allowed for Apps targeting a SDK
     * version less than Q
     */
    @Test
    public void testEnabledNetworkWithDisableOthersAllowedForAppsTargetingBelowQSdk()
            throws Exception {
        mLooper.dispatchAll();
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(NetworkUpdateResult result, ActionListenerWrapper callback,
                    int callingUid) {
                callback.sendSuccess(); // return success
            }
        }).when(mConnectHelper).connectToNetwork(
                eq(new NetworkUpdateResult(TEST_NETWORK_ID)), any(), anyInt());

        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.enableNetwork(TEST_NETWORK_ID, true, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatch();

        verify(mConnectHelper).connectToNetwork(
                eq(new NetworkUpdateResult(TEST_NETWORK_ID)), any(), anyInt());
        verify(mWifiMetrics).incrementNumEnableNetworkCalls();
    }

    /**
     * Verify that enableNetwork (with disableOthers=false) is allowed for Apps targeting a SDK
     * version less than Q
     */
    @Test
    public void testEnabledNetworkWithoutDisableOthersAllowedForAppsTargetingBelowQSdk()
            throws Exception {
        mLooper.dispatchAll();
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        when(mWifiConfigManager.enableNetwork(anyInt(), anyBoolean(), anyInt(), anyString()))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.enableNetwork(TEST_NETWORK_ID, false, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWifiConfigManager).enableNetwork(eq(TEST_NETWORK_ID), eq(false),
                eq(Binder.getCallingUid()), eq(TEST_PACKAGE_NAME));
        verify(mWifiMetrics).incrementNumEnableNetworkCalls();
    }

    /**
     * Verify that enableNetwork is not allowed for Apps targeting Q SDK
     */
    @Test
    public void testEnableNetworkNotAllowedForAppsTargetingQ() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);

        mLooper.startAutoDispatch();
        mWifiServiceImpl.enableNetwork(TEST_NETWORK_ID, true, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mConnectHelper, never()).connectToNetwork(any(), any(), anyInt());
        verify(mWifiMetrics, never()).incrementNumEnableNetworkCalls();
    }

    /**
     * Ensure that we invoke {@link WifiNetworkSuggestionsManager} to add network
     * suggestions.
     */
    @Test
    public void testAddNetworkSuggestions() {
        when(mWifiNetworkSuggestionsManager.add(any(), anyInt(), anyString(),
                nullable(String.class))).thenReturn(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS);
        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                mWifiServiceImpl.addNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME,
                        TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        when(mWifiNetworkSuggestionsManager.add(any(), anyInt(), anyString(),
                nullable(String.class))).thenReturn(
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE);
        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
                mWifiServiceImpl.addNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME,
                        TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager, times(2)).add(
                any(), eq(Binder.getCallingUid()), eq(TEST_PACKAGE_NAME), eq(TEST_FEATURE_ID));
    }

    /**
     * Ensure that we don't invoke {@link WifiNetworkSuggestionsManager} to add network
     * suggestions when the looper sync call times out.
     */
    @Test
    public void testAddNetworkSuggestionsFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL,
                mWifiServiceImpl.addNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME,
                        TEST_FEATURE_ID));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager, never()).add(any(), eq(Binder.getCallingUid()),
                eq(TEST_PACKAGE_NAME), eq(TEST_FEATURE_ID));
    }

    /**
     * Ensure that we invoke {@link WifiNetworkSuggestionsManager} to remove network
     * suggestions.
     */
    @Test
    public void testRemoveNetworkSuggestions() {
        when(mWifiNetworkSuggestionsManager.remove(any(), anyInt(), anyString()))
                .thenReturn(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID);
        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID,
                mWifiServiceImpl.removeNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        when(mWifiNetworkSuggestionsManager.remove(any(), anyInt(), anyString()))
                .thenReturn(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS);
        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                mWifiServiceImpl.removeNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager, times(2)).remove(any(), anyInt(),
                eq(TEST_PACKAGE_NAME));
    }

    /**
     * Ensure that we don't invoke {@link WifiNetworkSuggestionsManager} to remove network
     * suggestions when the looper sync call times out.
     */
    @Test
    public void testRemoveNetworkSuggestionsFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL,
                mWifiServiceImpl.removeNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager, never()).remove(any(), anyInt(),
                eq(TEST_PACKAGE_NAME));
    }

    /**
     * Ensure that we invoke {@link WifiNetworkSuggestionsManager} to get network
     * suggestions.
     */
    @Test
    public void testGetNetworkSuggestions() {
        List<WifiNetworkSuggestion> testList = new ArrayList<>();
        when(mWifiNetworkSuggestionsManager.get(anyString())).thenReturn(testList);
        mLooper.startAutoDispatch();
        assertEquals(testList, mWifiServiceImpl.getNetworkSuggestions(TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager).get(eq(TEST_PACKAGE_NAME));
    }

    /**
     * Ensure that we don't invoke {@link WifiNetworkSuggestionsManager} to get network
     * suggestions when the looper sync call times out.
     */
    @Test
    public void testGetNetworkSuggestionsFailureInRunWithScissors() {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.getNetworkSuggestions(TEST_PACKAGE_NAME).isEmpty());
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        verify(mWifiNetworkSuggestionsManager, never()).get(eq(TEST_PACKAGE_NAME));
    }

    /**
     * Verify that if the caller has NETWORK_SETTINGS permission, then it can invoke
     * {@link WifiManager#disableEphemeralNetwork(String)}.
     */
    @Test
    public void testDisableEphemeralNetworkWithNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl.disableEphemeralNetwork(new String(), TEST_PACKAGE_NAME);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).userTemporarilyDisabledNetwork(anyString(), anyInt());
    }

    /**
     * Verify that if the caller does not have NETWORK_SETTINGS permission, then it cannot invoke
     * {@link WifiManager#disableEphemeralNetwork(String)}.
     */
    @Test
    public void testDisableEphemeralNetworkWithoutNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        mWifiServiceImpl.disableEphemeralNetwork(new String(), TEST_PACKAGE_NAME);
        mLooper.dispatchAll();
        verify(mWifiConfigManager, never()).userTemporarilyDisabledNetwork(anyString(), anyInt());
    }

    /**
     * Verify getting the factory MAC address.
     */
    @Test
    public void testGetFactoryMacAddresses() throws Exception {
        when(mClientModeManager.getFactoryMacAddress()).thenReturn(TEST_FACTORY_MAC);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        mLooper.startAutoDispatch();
        final String[] factoryMacs = mWifiServiceImpl.getFactoryMacAddresses();
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertEquals(1, factoryMacs.length);
        assertEquals(TEST_FACTORY_MAC, factoryMacs[0]);
        verify(mClientModeManager).getFactoryMacAddress();
    }

    /**
     * Verify getting the factory MAC address returns null when posting the runnable to handler
     * fails.
     */
    @Test
    public void testGetFactoryMacAddressesPostFail() throws Exception {
        mWifiServiceImpl = makeWifiServiceImplWithMockRunnerWhichTimesOut();

        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        mLooper.startAutoDispatch();
        assertArrayEquals(new String[0], mWifiServiceImpl.getFactoryMacAddresses());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mClientModeManager, never()).getFactoryMacAddress();
    }

    /**
     * Verify getting the factory MAC address returns null when the lower layers fail.
     */
    @Test
    public void testGetFactoryMacAddressesFail() throws Exception {
        when(mClientModeManager.getFactoryMacAddress()).thenReturn(null);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        mLooper.startAutoDispatch();
        assertArrayEquals(new String[0], mWifiServiceImpl.getFactoryMacAddresses());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mClientModeManager).getFactoryMacAddress();
    }

    /**
     * Verify getting the factory MAC address throws a SecurityException if the calling app
     * doesn't have NETWORK_SETTINGS permission.
     */
    @Test
    public void testGetFactoryMacAddressesFailNoNetworkSettingsPermission() throws Exception {
        when(mClientModeManager.getFactoryMacAddress()).thenReturn(TEST_FACTORY_MAC);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        try {
            mLooper.startAutoDispatch();
            mWifiServiceImpl.getFactoryMacAddresses();
            mLooper.stopAutoDispatchAndIgnoreExceptions();
            fail();
        } catch (SecurityException e) {
            assertTrue("Exception message should contain 'factory MAC'",
                    e.toString().contains("factory MAC"));
        }
    }

    /**
     * Verify that a call to setDeviceMobilityState throws a SecurityException if the
     * caller does not have WIFI_SET_DEVICE_MOBILITY_STATE permission.
     */
    @Test(expected = SecurityException.class)
    public void setDeviceMobilityStateThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.WIFI_SET_DEVICE_MOBILITY_STATE),
                        eq("WifiService"));
        mWifiServiceImpl.setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
    }

    /**
     * Verifies that setDeviceMobilityState runs on a separate handler thread.
     */
    @Test
    public void setDeviceMobilityStateRunsOnHandler() {
        mWifiServiceImpl.setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        verify(mWifiConnectivityManager, never())
                .setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        verify(mWifiHealthMonitor, never())
                .setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        verify(mWifiDataStall, never())
                .setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        mLooper.dispatchAll();
        verify(mWifiConnectivityManager).setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        verify(mWifiHealthMonitor).setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
        verify(mWifiDataStall).setDeviceMobilityState(DEVICE_MOBILITY_STATE_STATIONARY);
    }

    /**
     * Verify that a call to addOnWifiUsabilityStatsListener throws a SecurityException if
     * the caller does not have WIFI_UPDATE_USABILITY_STATS_SCORE permission.
     */
    @Test
    public void testAddStatsListenerThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.addOnWifiUsabilityStatsListener(mAppBinder,
                    mOnWifiUsabilityStatsListener, TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to addOnWifiUsabilityStatsListener throws an IllegalArgumentException
     * if the parameters are not provided.
     */
    @Test
    public void testAddStatsListenerThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.addOnWifiUsabilityStatsListener(
                    mAppBinder, null, TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to removeOnWifiUsabilityStatsListener throws a SecurityException if
     * the caller does not have WIFI_UPDATE_USABILITY_STATS_SCORE permission.
     */
    @Test
    public void testRemoveStatsListenerThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.removeOnWifiUsabilityStatsListener(
                    TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that addOnWifiUsabilityStatsListener adds listener to {@link WifiMetrics}.
     */
    @Test
    public void testAddOnWifiUsabilityStatsListenerAndVerify() throws Exception {
        mWifiServiceImpl.addOnWifiUsabilityStatsListener(mAppBinder, mOnWifiUsabilityStatsListener,
                TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mWifiMetrics).addOnWifiUsabilityListener(mAppBinder, mOnWifiUsabilityStatsListener,
                TEST_WIFI_USABILITY_STATS_LISTENER_IDENTIFIER);
    }

    /**
     * Verify that removeOnWifiUsabilityStatsListener removes listener from
     * {@link WifiMetrics}.
     */
    @Test
    public void testRemoveOnWifiUsabilityStatsListenerAndVerify() throws Exception {
        mWifiServiceImpl.removeOnWifiUsabilityStatsListener(0);
        mLooper.dispatchAll();
        verify(mWifiMetrics).removeOnWifiUsabilityListener(0);
    }

    /**
     * Verify that a call to updateWifiUsabilityScore throws a SecurityException if the
     * caller does not have UPDATE_WIFI_USABILITY_SCORE permission.
     */
    @Test
    public void testUpdateWifiUsabilityScoreThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE),
                eq("WifiService"));
        try {
            mWifiServiceImpl.updateWifiUsabilityScore(anyInt(), anyInt(), 15);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that mClientModeManager in WifiServiceImpl is being updated on Wifi usability score
     * update event.
     */
    @Test
    public void testWifiUsabilityScoreUpdateAfterScoreEvent() {
        mWifiServiceImpl.updateWifiUsabilityScore(5, 10, 15);
        mLooper.dispatchAll();
        verify(mWifiMetrics).incrementWifiUsabilityScoreCount(WIFI_IFACE_NAME, 5, 10, 15);
    }

    private void startLohsAndTethering(boolean isApConcurrencySupported) throws Exception {
        // initialization
        when(mActiveModeWarden.canRequestMoreSoftApManagers(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME))))
                .thenReturn(isApConcurrencySupported);
        // For these tests, always use distinct interface names for LOHS and tethered.
        mLohsInterfaceName = WIFI_IFACE_NAME2;

        mLooper.startAutoDispatch();
        setupLocalOnlyHotspot();
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        reset(mActiveModeWarden);

        when(mActiveModeWarden.canRequestMoreSoftApManagers(
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME))))
                .thenReturn(isApConcurrencySupported);

        // start tethering
        mLooper.startAutoDispatch();
        boolean tetheringResult = mWifiServiceImpl.startSoftAp(null, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertTrue(tetheringResult);
        verify(mActiveModeWarden).startSoftAp(any(),
                eq(new WorkSource(Binder.getCallingUid(), TEST_PACKAGE_NAME)));
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
    }

    /**
     * Verify LOHS gets stopped when trying to start tethering concurrently on devices that
     * doesn't support dual AP operation.
     */
    @Test
    public void testStartLohsAndTethering1AP() throws Exception {
        startLohsAndTethering(false);

        // verify LOHS got stopped
        verify(mLohsCallback).onHotspotFailed(anyInt());
        verify(mActiveModeWarden).stopSoftAp(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
    }

    /**
     * Verify LOHS doesn't get stopped when trying to start tethering concurrently on devices
     * that does support dual AP operation.
     */
    @Test
    public void testStartLohsAndTethering2AP() throws Exception {
        startLohsAndTethering(true);

        // verify LOHS didn't get stopped
        verifyZeroInteractions(ignoreStubs(mLohsCallback));
        verify(mActiveModeWarden, never()).stopSoftAp(anyInt());
    }

    /**
     * Verify that the call to startDppAsConfiguratorInitiator throws a security exception when the
     * caller doesn't have NETWORK_SETTINGS permissions or NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testStartDppAsConfiguratorInitiatorWithoutPermissions() {
        mWifiServiceImpl.startDppAsConfiguratorInitiator(mAppBinder, DPP_URI,
                1, 1, mDppCallback);
    }

    /**
     * Verify that the call to startDppAsEnrolleeInitiator throws a security exception when the
     * caller doesn't have NETWORK_SETTINGS permissions or NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testStartDppAsEnrolleeInitiatorWithoutPermissions() {
        mWifiServiceImpl.startDppAsEnrolleeInitiator(mAppBinder, DPP_URI, mDppCallback);
    }

    /**
     * Verify that the call to startDppAsEnrolleeResponder throws a security exception when the
     * caller doesn't have NETWORK_SETTINGS permissions or NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testStartDppAsEnrolleeResponderWithoutPermissions() {
        mWifiServiceImpl.startDppAsEnrolleeResponder(mAppBinder, DPP_PRODUCT_INFO,
                EASY_CONNECT_CRYPTOGRAPHY_CURVE_PRIME256V1, mDppCallback);
    }

    /**
     * Verify that a call to StartDppAsEnrolleeResponder throws an IllegalArgumentException
     * if the deviceInfo length exceeds the max allowed length.
     */
    @Test(expected = SecurityException.class)
    public void testStartDppAsEnrolleeResponderThrowsIllegalArgumentExceptionOnDeviceInfoMaxLen() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Strings.repeat("a",
                    WifiManager.EASY_CONNECT_DEVICE_INFO_MAXIMUM_LENGTH + 2));
            String deviceInfo = sb.toString();
            mWifiServiceImpl.startDppAsEnrolleeResponder(mAppBinder, deviceInfo,
                    EASY_CONNECT_CRYPTOGRAPHY_CURVE_PRIME256V1, mDppCallback);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to StartDppAsEnrolleeResponder throws an IllegalArgumentException
     * if the deviceInfo contains characters which are not allowed as per spec (For example
     * semicolon)
     */
    @Test(expected = SecurityException.class)
    public void testStartDppAsEnrolleeResponderThrowsIllegalArgumentExceptionOnWrongDeviceInfo() {
        try {
            mWifiServiceImpl.startDppAsEnrolleeResponder(mAppBinder, "DPP;TESTER",
                    EASY_CONNECT_CRYPTOGRAPHY_CURVE_PRIME256V1, mDppCallback);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that the call to stopDppSession throws a security exception when the
     * caller doesn't have NETWORK_SETTINGS permissions or NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testStopDppSessionWithoutPermissions() {
        try {
            mWifiServiceImpl.stopDppSession();
        } catch (RemoteException e) {
        }
    }

    /**
     * Verifies that configs can be removed.
     */
    @Test
    public void testRemoveNetworkIsAllowedForAppsTargetingBelowQSdk() {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiConfigManager.removeNetwork(eq(0), anyInt(), anyString())).thenReturn(true);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);

        mLooper.startAutoDispatch();
        boolean result = mWifiServiceImpl.removeNetwork(0, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        assertTrue(result);
        verify(mWifiConfigManager).removeNetwork(anyInt(), anyInt(), anyString());
    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is allowed for apps targeting below R SDK.
     */
    @Test
    public void addOrUpdatePasspointConfigIsAllowedForAppsTargetingBelowRSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(true);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        reset(mPasspointManager);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(false);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is not allowed for apps targeting R SDK.
     */
    @Test
    public void addOrUpdatePasspointConfigIsNotAllowedForAppsTargetingRSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager, never())
                .addOrUpdateProvider(any(), anyInt(), anyString(), anyBoolean(), anyBoolean());

    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is allowed for Settings apps.
     */
    @Test
    public void addOrUpdatePasspointConfigIsAllowedSettingsApp() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(false);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).addOrUpdateProvider(any(), anyInt(), anyString(), anyBoolean(),
                anyBoolean());
    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is allowed for System apps.
     */
    @Test
    public void addOrUpdatePasspointConfigIsAllowedSystemApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isSystem(anyString(), anyInt())).thenReturn(true);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).addOrUpdateProvider(any(), anyInt(), anyString(), anyBoolean(),
                anyBoolean());
    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is allowed for DeviceOwner apps.
     */
    @Test
    public void addOrUpdatePasspointConfigIsAllowedSystemAlertDOApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isDeviceOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).addOrUpdateProvider(any(), anyInt(), anyString(), anyBoolean(),
                anyBoolean());
    }

    /**
     * Verify that addOrUpdatePasspointConfiguration is allowed for ProfileOwner apps.
     */
    @Test
    public void addOrUpdatePasspointConfigIsAllowedSystemAlertPOApp() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.R), anyInt())).thenReturn(false);
        when(mWifiPermissionsUtil.isProfileOwner(Binder.getCallingUid(), TEST_PACKAGE_NAME))
                .thenReturn(true);
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        config.setHomeSp(homeSp);

        when(mPasspointManager.addOrUpdateProvider(
                config, Binder.getCallingUid(), TEST_PACKAGE_NAME, false, true))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.addOrUpdatePasspointConfiguration(config, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mPasspointManager).addOrUpdateProvider(any(), anyInt(), anyString(), anyBoolean(),
                anyBoolean());
    }

    /**
     * Verify that removePasspointConfiguration will redirect calls to {@link PasspointManager}
     * and returning the result that's returned from {@link PasspointManager}.
     */
    @Test
    public void removePasspointConfig() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);

        String fqdn = "test.com";
        when(mPasspointManager.removeProvider(anyInt(), anyBoolean(), isNull(), eq(fqdn)))
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.removePasspointConfiguration(fqdn, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        reset(mPasspointManager);

        when(mPasspointManager.removeProvider(anyInt(), anyBoolean(), isNull(), eq(fqdn)))
                .thenReturn(false);
        mLooper.startAutoDispatch();
        assertFalse(mWifiServiceImpl.removePasspointConfiguration(fqdn, TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Test that DISABLE_NETWORK returns failure to public API when WifiConfigManager returns
     * failure.
     */
    @Test
    public void testDisableNetworkFailureAppBelowQSdk() throws Exception {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mWifiPermissionsUtil.isTargetSdkLessThan(anyString(),
                eq(Build.VERSION_CODES.Q), anyInt())).thenReturn(true);
        when(mWifiConfigManager.disableNetwork(anyInt(), anyInt(), anyString())).thenReturn(false);

        mLooper.startAutoDispatch();
        boolean succeeded = mWifiServiceImpl.disableNetwork(0, TEST_PACKAGE_NAME);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        assertFalse(succeeded);
    }

    @Test
    public void testAllowAutojoinGlobalFailureNoNetworkSettingsPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.allowAutojoinGlobal(true);
            fail("Expected SecurityException");
        } catch (SecurityException e) {
            // Test succeeded
        }
    }

    @Test
    public void testAllowAutojoinFailureNoNetworkSettingsPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.allowAutojoin(0, true);
            fail("Expected SecurityException");
        } catch (SecurityException e) {
            // Test succeeded
        }
    }

    @Test
    public void testAllowAutojoinOnSuggestionNetwork() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowAutojoin = false;
        config.fromWifiNetworkSuggestion = true;
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);
        when(mWifiNetworkSuggestionsManager.allowNetworkSuggestionAutojoin(any(), anyBoolean()))
                .thenReturn(true);
        mWifiServiceImpl.allowAutojoin(0, true);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).getConfiguredNetwork(0);
        verify(mWifiNetworkSuggestionsManager).allowNetworkSuggestionAutojoin(any(), anyBoolean());
        verify(mWifiConfigManager).allowAutojoin(anyInt(), anyBoolean());
        verify(mWifiMetrics).logUserActionEvent(eq(UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON),
                anyInt());
    }

    @Test
    public void testAllowAutojoinOnSavedNetwork() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowAutojoin = false;
        config.fromWifiNetworkSuggestion = false;
        config.fromWifiNetworkSpecifier = false;
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);
        mWifiServiceImpl.allowAutojoin(0, true);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).getConfiguredNetwork(0);
        verify(mWifiNetworkSuggestionsManager, never())
                .allowNetworkSuggestionAutojoin(any(), anyBoolean());
        verify(mWifiConfigManager).allowAutojoin(anyInt(), anyBoolean());
    }

    @Test
    public void testAllowAutojoinOnWifiNetworkSpecifier() {
        WifiConfiguration config = new WifiConfiguration();
        config.fromWifiNetworkSpecifier = true;
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);
        mWifiServiceImpl.allowAutojoin(0, true);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).getConfiguredNetwork(0);
        verify(mWifiNetworkSuggestionsManager, never())
                .allowNetworkSuggestionAutojoin(config, true);
        verify(mWifiConfigManager, never()).allowAutojoin(0, true);
    }

    @Test
    public void testAllowAutojoinOnSavedPasspointNetwork() {
        WifiConfiguration config = WifiConfigurationTestUtil.createPasspointNetwork();
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);
        when(mWifiNetworkSuggestionsManager.allowNetworkSuggestionAutojoin(any(), anyBoolean()))
                .thenReturn(true);
        mWifiServiceImpl.allowAutojoin(0, true);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).getConfiguredNetwork(0);
        verify(mWifiNetworkSuggestionsManager, never())
                .allowNetworkSuggestionAutojoin(config, true);
        verify(mWifiConfigManager, never()).allowAutojoin(0, true);
    }

    /**
     * Test that setMacRandomizationSettingPasspointEnabled is protected by NETWORK_SETTINGS
     * permission.
     */
    @Test
    public void testSetMacRandomizationSettingPasspointEnabledFailureNoNetworkSettingsPermission()
            throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.setMacRandomizationSettingPasspointEnabled("TEST_FQDN", true);
            fail("Expected SecurityException");
        } catch (SecurityException e) {
            // Test succeeded
        }
    }

    /**
     * Test that setMacRandomizationSettingPasspointEnabled makes the appropriate calls.
     */
    @Test
    public void testSetMacRandomizationSettingPasspointEnabled() throws Exception {
        mWifiServiceImpl.setMacRandomizationSettingPasspointEnabled("TEST_FQDN", true);
        mLooper.dispatchAll();
        verify(mPasspointManager).enableMacRandomization("TEST_FQDN", true);
    }

    /**
     * Test that setPasspointMeteredOverride is protected by NETWORK_SETTINGS permission.
     */
    @Test
    public void testSetPasspointMeteredOverrideFailureNoNetworkSettingsPermission()
            throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.setPasspointMeteredOverride("TEST_FQDN", METERED_OVERRIDE_METERED);
            fail("Expected SecurityException");
        } catch (SecurityException e) {
            // Test succeeded
        }
    }

    /**
     * Test that setPasspointMeteredOverride makes the appropriate calls.
     */
    @Test
    public void testSetPasspointMeteredOverride() throws Exception {
        mWifiServiceImpl.setPasspointMeteredOverride("TEST_FQDN", METERED_OVERRIDE_METERED);
        mLooper.dispatchAll();
        verify(mPasspointManager).setMeteredOverride("TEST_FQDN", METERED_OVERRIDE_METERED);
    }

    /**
     * Test handle boot completed sequence.
     */
    @Test
    public void testHandleBootCompleted() throws Exception {
        when(mWifiInjector.getPasspointProvisionerHandlerThread())
                .thenReturn(mock(HandlerThread.class));
        mLooper.startAutoDispatch();
        mWifiServiceImpl.handleBootCompleted();
        mLooper.stopAutoDispatch();

        verify(mWifiNetworkFactory).register();
        verify(mUntrustedWifiNetworkFactory).register();
        verify(mPasspointManager).initializeProvisioner(any());
        verify(mWifiP2pConnection).handleBootCompleted();
    }

    /**
     * Test handle user switch sequence.
     */
    @Test
    public void testHandleUserSwitch() throws Exception {
        mWifiServiceImpl.handleUserSwitch(5);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).handleUserSwitch(5);
    }

    /**
     * Test handle user unlock sequence.
     */
    @Test
    public void testHandleUserUnlock() throws Exception {
        mWifiServiceImpl.handleUserUnlock(5);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).handleUserUnlock(5);
    }

    /**
     * Test handle user stop sequence.
     */
    @Test
    public void testHandleUserStop() throws Exception {
        mWifiServiceImpl.handleUserStop(5);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).handleUserStop(5);
    }

    /**
     * Test register scan result callback without permission.
     */
    @Test(expected = SecurityException.class)
    public void testRegisterScanResultCallbackWithMissingPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(ACCESS_WIFI_STATE), eq("WifiService"));
        mWifiServiceImpl.registerScanResultsCallback(mScanResultsCallback);
    }

    /**
     * Test unregister scan result callback without permission.
     */
    @Test(expected = SecurityException.class)
    public void testUnregisterScanResultCallbackWithMissingPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(ACCESS_WIFI_STATE), eq("WifiService"));
        mWifiServiceImpl.unregisterScanResultsCallback(mScanResultsCallback);
    }

    /**
     * Test register scan result callback with illegal argument.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterScanResultCallbackWithIllegalArgument() throws Exception {
        mWifiServiceImpl.registerScanResultsCallback(null);
    }

    /**
     * Test register and unregister callback will go to ScanRequestProxy;
     */
    @Test
    public void testRegisterUnregisterScanResultCallback() throws Exception {
        mWifiServiceImpl.registerScanResultsCallback(mScanResultsCallback);
        mLooper.dispatchAll();
        verify(mScanRequestProxy).registerScanResultsCallback(mScanResultsCallback);
        mWifiServiceImpl.unregisterScanResultsCallback(mScanResultsCallback);
        mLooper.dispatchAll();
        verify(mScanRequestProxy).unregisterScanResultsCallback(mScanResultsCallback);
    }

    /**
     * Test register callback without permission.
     */
    @Test(expected = SecurityException.class)
    public void testRegisterSuggestionNetworkCallbackWithMissingPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(ACCESS_WIFI_STATE), eq("WifiService"));
        mWifiServiceImpl.registerSuggestionConnectionStatusListener(mAppBinder,
                mSuggestionConnectionStatusListener, NETWORK_CALLBACK_ID, TEST_PACKAGE_NAME,
                TEST_FEATURE_ID);
    }

    /**
     * Test register callback without callback
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterSuggestionNetworkCallbackWithIllegalArgument() {
        mWifiServiceImpl.registerSuggestionConnectionStatusListener(mAppBinder, null,
                NETWORK_CALLBACK_ID, TEST_PACKAGE_NAME, TEST_FEATURE_ID);
    }

    /**
     * Test unregister callback without permission.
     */
    @Test(expected = SecurityException.class)
    public void testUnregisterSuggestionNetworkCallbackWithMissingPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(ACCESS_WIFI_STATE), eq("WifiService"));
        mWifiServiceImpl.unregisterSuggestionConnectionStatusListener(
                NETWORK_CALLBACK_ID, TEST_PACKAGE_NAME);
    }

    /**
     * Test register nad unregister callback will go to WifiNetworkSuggestionManager
     */
    @Test
    public void testRegisterUnregisterSuggestionNetworkCallback() throws Exception {
        mWifiServiceImpl.registerSuggestionConnectionStatusListener(mAppBinder,
                mSuggestionConnectionStatusListener, NETWORK_CALLBACK_ID, TEST_PACKAGE_NAME,
                TEST_FEATURE_ID);
        mLooper.dispatchAll();
        verify(mWifiNetworkSuggestionsManager).registerSuggestionConnectionStatusListener(
                eq(mAppBinder), eq(mSuggestionConnectionStatusListener), eq(NETWORK_CALLBACK_ID),
                eq(TEST_PACKAGE_NAME));
        mWifiServiceImpl.unregisterSuggestionConnectionStatusListener(NETWORK_CALLBACK_ID,
                TEST_PACKAGE_NAME);
        mLooper.dispatchAll();
        verify(mWifiNetworkSuggestionsManager).unregisterSuggestionConnectionStatusListener(
                eq(NETWORK_CALLBACK_ID), eq(TEST_PACKAGE_NAME));
    }


    /**
     * Test to verify that the lock mode is verified before dispatching the operation
     *
     * Steps: call acquireWifiLock with an invalid lock mode.
     * Expected: the call should throw an IllegalArgumentException.
     */
    @Test(expected = IllegalArgumentException.class)
    public void acquireWifiLockShouldThrowExceptionOnInvalidLockMode() throws Exception {
        final int wifiLockModeInvalid = -1;

        mWifiServiceImpl.acquireWifiLock(mAppBinder, wifiLockModeInvalid, "", null);
    }

    private void setupReportActivityInfo() {
        WifiLinkLayerStats stats = new WifiLinkLayerStats();
        stats.on_time = 1000;
        stats.tx_time = 1;
        stats.rx_time = 2;
        stats.tx_time_per_level = new int[] {3, 4, 5};
        stats.on_time_scan = 6;
        when(mClientModeManager.getWifiLinkLayerStats()).thenReturn(stats);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE))
                .thenReturn(7.0);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX))
                .thenReturn(8.0);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX))
                .thenReturn(9.0);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE))
                .thenReturn(10000.0);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(9999L);
    }

    private void validateWifiActivityEnergyInfo(WifiActivityEnergyInfo info) {
        assertNotNull(info);
        assertEquals(9999L, info.getTimeSinceBootMillis());
        assertEquals(WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, info.getStackState());
        assertEquals(1, info.getControllerTxDurationMillis());
        assertEquals(2, info.getControllerRxDurationMillis());
        assertEquals(6, info.getControllerScanDurationMillis());
        assertEquals(997, info.getControllerIdleDurationMillis());
    }

    /**
     * Tests that {@link WifiServiceImpl#getWifiActivityEnergyInfoAsync} throws
     * {@link SecurityException} if the caller doesn't have the necessary permissions.
     */
    @Test(expected = SecurityException.class)
    public void getWifiActivityEnergyInfoAsyncNoPermission() throws Exception {
        doThrow(SecurityException.class)
                .when(mContext).enforceCallingOrSelfPermission(eq(ACCESS_WIFI_STATE), any());
        mWifiServiceImpl.getWifiActivityEnergyInfoAsync(mOnWifiActivityEnergyInfoListener);
    }

    /**
     * Tests that {@link WifiServiceImpl#getWifiActivityEnergyInfoAsync} passes null to the listener
     * if link layer stats is unsupported.
     */
    @Test
    public void getWifiActivityEnergyInfoAsyncFeatureUnsupported() throws Exception {
        when(mClientModeManager.getSupportedFeatures()).thenReturn(0L);
        mLooper.startAutoDispatch();
        mWifiServiceImpl.getWifiActivityEnergyInfoAsync(mOnWifiActivityEnergyInfoListener);
        mLooper.stopAutoDispatch();
        verify(mOnWifiActivityEnergyInfoListener).onWifiActivityEnergyInfo(null);
    }

    /**
     * Tests that {@link WifiServiceImpl#getWifiActivityEnergyInfoAsync} passes the expected values
     * to the listener on success.
     */
    @Test
    public void getWifiActivityEnergyInfoAsyncSuccess() throws Exception {
        when(mClientModeManager.getSupportedFeatures()).thenReturn(Long.MAX_VALUE);
        setupReportActivityInfo();
        mLooper.startAutoDispatch();
        mWifiServiceImpl.getWifiActivityEnergyInfoAsync(mOnWifiActivityEnergyInfoListener);
        mLooper.stopAutoDispatch();
        ArgumentCaptor<WifiActivityEnergyInfo> infoCaptor =
                ArgumentCaptor.forClass(WifiActivityEnergyInfo.class);
        verify(mOnWifiActivityEnergyInfoListener).onWifiActivityEnergyInfo(infoCaptor.capture());
        validateWifiActivityEnergyInfo(infoCaptor.getValue());
    }

    @Test
    public void testCarrierConfigChangeUpdateSoftApCapability() throws Exception {
        MockitoSession staticMockSession = mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        lenient().when(SubscriptionManager.getActiveDataSubscriptionId())
                .thenReturn(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)));

        // Send the broadcast
        Intent intent = new Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).updateSoftApCapability(any());
        staticMockSession.finishMocking();
    }

    @Test
    public void testPhoneActiveDataSubscriptionIdChangedUpdateSoftApCapability() throws Exception {
        MockitoSession staticMockSession = mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        lenient().when(SubscriptionManager.getActiveDataSubscriptionId())
                .thenReturn(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)));
        ArgumentCaptor<PhoneStateListener> phoneStateListenerCaptor =
                ArgumentCaptor.forClass(PhoneStateListener.class);
        verify(mTelephonyManager).listen(phoneStateListenerCaptor.capture(),
                eq(PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE));
        mPhoneStateListener = phoneStateListenerCaptor.getValue();
        assertNotNull(mPhoneStateListener);
        mPhoneStateListener.onActiveDataSubscriptionIdChanged(2);
        mLooper.dispatchAll();
        verify(mActiveModeWarden).updateSoftApCapability(any());
        staticMockSession.finishMocking();
    }

    /**
     * Verify that the call to getWifiConfigsForMatchedNetworkSuggestions is not redirected to
     * specific API getWifiConfigForMatchedNetworkSuggestionsSharedWithUser when the caller doesn't
     * have NETWORK_SETTINGS permissions and NETWORK_SETUP_WIZARD.
     */
    @Test(expected = SecurityException.class)
    public void testGetWifiConfigsForMatchedNetworkSuggestionsWithoutPermissions() {
        mWifiServiceImpl.getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(new ArrayList<>());
    }

    /**
     * Verify that the call to getWifiConfigsForMatchedNetworkSuggestions is redirected to
     * specific API getWifiConfigForMatchedNetworkSuggestionsSharedWithUser when the caller
     * have NETWORK_SETTINGS.
     */
    @Test
    public void testGetWifiConfigsForMatchedNetworkSuggestionsWithSettingPermissions() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mLooper.startAutoDispatch();
        mWifiServiceImpl
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(createScanResultList());
        mLooper.stopAutoDispatch();
        verify(mWifiNetworkSuggestionsManager)
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(any());
    }

    /**
     * Verify that the call to getWifiConfigsForMatchedNetworkSuggestions is redirected to
     * specific API getWifiConfigForMatchedNetworkSuggestionsSharedWithUser when the caller
     * have NETWORK_SETUP_WIZARD.
     */
    @Test
    public void testGetWifiConfigsForMatchedNetworkSuggestionsWithSetupWizardPermissions() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mLooper.startAutoDispatch();
        mWifiServiceImpl
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(createScanResultList());
        mLooper.stopAutoDispatch();
        verify(mWifiNetworkSuggestionsManager)
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(any());
    }

    @Test
    public void testGetWifiConfigsForMatchedNetworkSuggestionsWithInvalidScanResults() {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(new ArrayList<>());
        mLooper.dispatchAll();
        verify(mWifiNetworkSuggestionsManager, never())
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(any());
    }

    /**
     * Verify that a call to setWifiConnectedNetworkScorer throws a SecurityException if
     * the caller does not have WIFI_UPDATE_USABILITY_STATS_SCORE permission.
     */
    @Test
    public void testSetNetworkScorerThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.setWifiConnectedNetworkScorer(mAppBinder, mWifiConnectedNetworkScorer);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to setWifiConnectedNetworkScorer throws an IllegalArgumentException
     * if the parameters are not provided.
     */
    @Test
    public void testSetScorerThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.setWifiConnectedNetworkScorer(mAppBinder, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to clearWifiConnectedNetworkScorer throws a SecurityException if
     * the caller does not have WIFI_UPDATE_USABILITY_STATS_SCORE permission.
     */
    @Test
    public void testClearNetworkScorerThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE),
                                eq("WifiService"));
        try {
            mWifiServiceImpl.clearWifiConnectedNetworkScorer();
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that setWifiConnectedNetworkScorer sets scorer to {@link WifiScoreReport}.
     */
    @Test
    public void testSetWifiConnectedNetworkScorerAndVerify() throws Exception {
        mLooper.startAutoDispatch();
        mWifiServiceImpl.setWifiConnectedNetworkScorer(mAppBinder, mWifiConnectedNetworkScorer);
        mLooper.stopAutoDispatch();
        verify(mActiveModeWarden).setWifiConnectedNetworkScorer(
                mAppBinder, mWifiConnectedNetworkScorer);
    }

    /**
     * Verify that clearWifiConnectedNetworkScorer clears scorer from {@link WifiScoreReport}.
     */
    @Test
    public void testClearWifiConnectedNetworkScorerAndVerify() throws Exception {
        mWifiServiceImpl.clearWifiConnectedNetworkScorer();
        mLooper.dispatchAll();
        verify(mActiveModeWarden).clearWifiConnectedNetworkScorer();
    }

    private long testGetSupportedFeaturesCaseForRtt(
            long supportedFeaturesFromClientModeManager, boolean rttDisabled) {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)).thenReturn(
                !rttDisabled);
        when(mClientModeManager.getSupportedFeatures())
                .thenReturn(supportedFeaturesFromClientModeManager);
        mLooper.startAutoDispatch();
        long supportedFeatures = mWifiServiceImpl.getSupportedFeatures();
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        return supportedFeatures;
    }

    /** Verifies that syncGetSupportedFeatures() masks out capabilities based on system flags. */
    @Test
    public void syncGetSupportedFeaturesForRtt() {
        final long featureAware = WifiManager.WIFI_FEATURE_AWARE;
        final long featureInfra = WifiManager.WIFI_FEATURE_INFRA;
        final long featureD2dRtt = WifiManager.WIFI_FEATURE_D2D_RTT;
        final long featureD2apRtt = WifiManager.WIFI_FEATURE_D2AP_RTT;
        final long featureLongBits = 0x1000000000L;

        assertEquals(0, testGetSupportedFeaturesCaseForRtt(0, false));
        assertEquals(0, testGetSupportedFeaturesCaseForRtt(0, true));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCaseForRtt(featureAware | featureInfra, false));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCaseForRtt(featureAware | featureInfra, true));
        assertEquals(featureInfra | featureD2dRtt,
                testGetSupportedFeaturesCaseForRtt(featureInfra | featureD2dRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCaseForRtt(featureInfra | featureD2dRtt, true));
        assertEquals(featureInfra | featureD2apRtt,
                testGetSupportedFeaturesCaseForRtt(featureInfra | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCaseForRtt(featureInfra | featureD2apRtt, true));
        assertEquals(featureInfra | featureD2dRtt | featureD2apRtt,
                testGetSupportedFeaturesCaseForRtt(
                        featureInfra | featureD2dRtt | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCaseForRtt(
                        featureInfra | featureD2dRtt | featureD2apRtt, true));

        assertEquals(featureLongBits | featureInfra | featureD2dRtt | featureD2apRtt,
                testGetSupportedFeaturesCaseForRtt(
                        featureLongBits | featureInfra | featureD2dRtt | featureD2apRtt, false));
        assertEquals(featureLongBits | featureInfra,
                testGetSupportedFeaturesCaseForRtt(
                        featureLongBits | featureInfra | featureD2dRtt | featureD2apRtt, true));
    }

    private long testGetSupportedFeaturesCaseForMacRandomization(
            long supportedFeaturesFromClientModeManager, boolean apMacRandomizationEnabled,
            boolean staConnectedMacRandomizationEnabled, boolean p2pMacRandomizationEnabled) {
        when(mResources.getBoolean(
                R.bool.config_wifi_connected_mac_randomization_supported))
                .thenReturn(staConnectedMacRandomizationEnabled);
        when(mResources.getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported))
                .thenReturn(apMacRandomizationEnabled);
        when(mResources.getBoolean(
                R.bool.config_wifi_p2p_mac_randomization_supported))
                .thenReturn(p2pMacRandomizationEnabled);
        when(mClientModeManager.getSupportedFeatures())
                .thenReturn(supportedFeaturesFromClientModeManager);
        mLooper.startAutoDispatch();
        long supportedFeatures = mWifiServiceImpl.getSupportedFeatures();
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        return supportedFeatures;
    }

    /** Verifies that syncGetSupportedFeatures() masks out capabilities based on system flags. */
    @Test
    public void syncGetSupportedFeaturesForMacRandomization() {
        final long featureStaConnectedMacRandomization =
                WifiManager.WIFI_FEATURE_CONNECTED_RAND_MAC;
        final long featureApMacRandomization =
                WifiManager.WIFI_FEATURE_AP_RAND_MAC;
        final long featureP2pMacRandomization =
                WifiManager.WIFI_FEATURE_CONNECTED_RAND_MAC;

        assertEquals(featureStaConnectedMacRandomization | featureApMacRandomization
                        | featureP2pMacRandomization,
                testGetSupportedFeaturesCaseForMacRandomization(
                        featureP2pMacRandomization, true, true, true));
        // p2p supported by HAL, but disabled by overlay.
        assertEquals(featureStaConnectedMacRandomization | featureApMacRandomization,
                testGetSupportedFeaturesCaseForMacRandomization(
                        featureP2pMacRandomization, true, true, false));
        assertEquals(featureStaConnectedMacRandomization | featureApMacRandomization,
                testGetSupportedFeaturesCaseForMacRandomization(0, true, true, false));
    }

    @Test
    public void getSupportedFeaturesVerboseLoggingThrottled() {
        mWifiServiceImpl.enableVerboseLogging(1); // this logs
        when(mClock.getElapsedSinceBootMillis()).thenReturn(1000L);
        testGetSupportedFeaturesCaseForMacRandomization(0, true, true, false);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(1001L);
        testGetSupportedFeaturesCaseForMacRandomization(0, true, true, false); // should not log
        when(mClock.getElapsedSinceBootMillis()).thenReturn(5000L);
        testGetSupportedFeaturesCaseForMacRandomization(0, true, true, false);
        testGetSupportedFeaturesCaseForMacRandomization(0, true, false, false);
        verify(mLog, times(4)).info(any());
    }

    /**
     * Verifies that syncGetSupportedFeatures() adds capabilities based on interface
     * combination.
     */
    @Test
    public void syncGetSupportedFeaturesForStaApConcurrency() {
        long supportedFeaturesFromClientModeManager = WifiManager.WIFI_FEATURE_OWE;
        when(mClientModeManager.getSupportedFeatures())
                .thenReturn(supportedFeaturesFromClientModeManager);

        when(mActiveModeWarden.isStaApConcurrencySupported())
                .thenReturn(false);
        mLooper.startAutoDispatch();
        assertEquals(supportedFeaturesFromClientModeManager,
                        mWifiServiceImpl.getSupportedFeatures());
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        when(mActiveModeWarden.isStaApConcurrencySupported())
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertEquals(supportedFeaturesFromClientModeManager | WifiManager.WIFI_FEATURE_AP_STA,
                mWifiServiceImpl.getSupportedFeatures());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    /**
     * Verify startTemporarilyDisablingAllNonCarrierMergedWifi is guarded by NETWORK_SETTINGS
     * permission.
     */
    @Test
    public void testStartTemporarilyDisablingAllNonCarrierMergedWifiPermission() {
        assumeTrue(SdkLevel.isAtLeastS());
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.startTemporarilyDisablingAllNonCarrierMergedWifi(1);
            fail();
        } catch (SecurityException e) {
            // pass
        }
    }

    /**
     * Verify startTemporarilyDisablingAllNonCarrierMergedWifi works properly with permission.
     */
    @Test
    public void testStartTemporarilyDisablingAllNonCarrierMergedWifi() {
        assumeTrue(SdkLevel.isAtLeastS());
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.startTemporarilyDisablingAllNonCarrierMergedWifi(1);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).startTemporarilyDisablingAllNonCarrierMergedWifi(1);
        verify(mClientModeManager).disconnect();
    }

    /**
     * Verify stopTemporarilyDisablingAllNonCarrierMergedWifi is guarded by NETWORK_SETTINGS
     * permission.
     */
    @Test
    public void testStopTemporarilyDisablingAllNonCarrierMergedWifiPermission() {
        assumeTrue(SdkLevel.isAtLeastS());
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.stopTemporarilyDisablingAllNonCarrierMergedWifi();
            fail();
        } catch (SecurityException e) {
            // pass
        }
    }

    /**
     * Verify stopTemporarilyDisablingAllNonCarrierMergedWifi works properly with permission.
     */
    @Test
    public void testStopTemporarilyDisablingAllNonCarrierMergedWifi() {
        assumeTrue(SdkLevel.isAtLeastS());
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.stopTemporarilyDisablingAllNonCarrierMergedWifi();
        mLooper.dispatchAll();
        verify(mWifiConfigManager).stopTemporarilyDisablingAllNonCarrierMergedWifi();
    }

    /**
     * Verifies that syncGetSupportedFeatures() adds capabilities based on interface
     * combination.
     */
    @Test
    public void syncGetSupportedFeaturesForStaStaConcurrency() {
        assumeTrue(SdkLevel.isAtLeastS());

        long supportedFeaturesFromClientModeManager = WifiManager.WIFI_FEATURE_OWE;
        when(mClientModeManager.getSupportedFeatures())
                .thenReturn(supportedFeaturesFromClientModeManager);

        when(mActiveModeWarden.isStaStaConcurrencySupported())
                .thenReturn(false);
        mLooper.startAutoDispatch();
        assertEquals(supportedFeaturesFromClientModeManager,
                mWifiServiceImpl.getSupportedFeatures());
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        when(mActiveModeWarden.isStaStaConcurrencySupported())
                .thenReturn(true);
        mLooper.startAutoDispatch();
        assertEquals(supportedFeaturesFromClientModeManager
                        | WifiManager.WIFI_FEATURE_ADDITIONAL_STA,
                mWifiServiceImpl.getSupportedFeatures());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    @Test
    public void testSetScanThrottleEnabledWithNetworkSettingsPermission() {
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.setScanThrottleEnabled(true);
        mLooper.dispatchAll();
        verify(mScanRequestProxy).setScanThrottleEnabled(true);

        mWifiServiceImpl.setScanThrottleEnabled(false);
        mLooper.dispatchAll();
        verify(mScanRequestProxy).setScanThrottleEnabled(false);
    }

    @Test(expected = SecurityException.class)
    public void testSetScanThrottleEnabledWithNoNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));

        mWifiServiceImpl.setScanThrottleEnabled(true);
        mLooper.dispatchAll();
        verify(mScanRequestProxy, never()).setScanThrottleEnabled(true);
    }

    @Test
    public void testIsScanThrottleEnabled() {
        when(mScanRequestProxy.isScanThrottleEnabled()).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.isScanThrottleEnabled());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mScanRequestProxy).isScanThrottleEnabled();
    }

    @Test
    public void testSetAutoWakeupEnabledWithNetworkSettingsPermission() {
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.setAutoWakeupEnabled(true);
        mLooper.dispatchAll();
        verify(mWakeupController).setEnabled(true);

        mWifiServiceImpl.setAutoWakeupEnabled(false);
        mLooper.dispatchAll();
        verify(mWakeupController).setEnabled(false);
    }

    @Test(expected = SecurityException.class)
    public void testSetAutoWakeupEnabledWithNoNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));

        mWifiServiceImpl.setAutoWakeupEnabled(true);
        mLooper.dispatchAll();
        verify(mWakeupController, never()).setEnabled(true);
    }

    @Test
    public void testIsAutoWakeupEnabled() {
        when(mWakeupController.isEnabled()).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mWifiServiceImpl.isAutoWakeupEnabled());
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWakeupController).isEnabled();
    }

    @Test
    public void testSetScanAlwaysAvailableWithNetworkSettingsPermission() {
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.setScanAlwaysAvailable(true, TEST_PACKAGE_NAME);
        verify(mSettingsStore).handleWifiScanAlwaysAvailableToggled(true);
        verify(mActiveModeWarden).scanAlwaysModeChanged();

        mWifiServiceImpl.setScanAlwaysAvailable(false, TEST_PACKAGE_NAME);
        verify(mSettingsStore).handleWifiScanAlwaysAvailableToggled(false);
        verify(mActiveModeWarden, times(2)).scanAlwaysModeChanged();
    }

    @Test(expected = SecurityException.class)
    public void testSetScanAlwaysAvailableWithNoNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));

        mWifiServiceImpl.setScanAlwaysAvailable(true, TEST_PACKAGE_NAME);
        verify(mSettingsStore, never()).handleWifiScanAlwaysAvailableToggled(anyBoolean());
        verify(mActiveModeWarden, never()).scanAlwaysModeChanged();
    }

    @Test
    public void testIsScanAlwaysAvailable() {
        when(mSettingsStore.isScanAlwaysAvailableToggleEnabled()).thenReturn(true);
        assertTrue(mWifiServiceImpl.isScanAlwaysAvailable());
        verify(mSettingsStore).isScanAlwaysAvailableToggleEnabled();
    }

    private List<ScanResult> createScanResultList() {
        return Collections.singletonList(new ScanResult(WifiSsid.createFromAsciiEncoded(TEST_SSID),
                TEST_SSID, TEST_BSSID, 1245, 0, TEST_CAP, -78, 2450, 1025, 22, 33, 20, 0, 0, true));
    }

    private void sendCountryCodeChangedBroadcast(String countryCode) {
        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, countryCode);
        assertNotNull(mBroadcastReceiverCaptor.getValue());
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
    }

    @Test
    public void testCountryCodeBroadcastHanding() {
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)));
        sendCountryCodeChangedBroadcast("US");
        verify(mWifiCountryCode).setCountryCodeAndUpdate(any());
        verify(mActiveModeWarden).updateSoftApCapability(any());
    }

    @Test
    public void testDumpShouldDumpWakeupController() {
        mLooper.startAutoDispatch();
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), null);
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWakeupController).dump(any(), any(), any());
    }

    @Test
    public void testGetNetworkSuggestionUserApprovalStatus() {
        when(mWifiNetworkSuggestionsManager
                .getNetworkSuggestionUserApprovalStatus(anyInt(), anyString()))
                .thenReturn(WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER);
        mLooper.startAutoDispatch();
        assertEquals(WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER,
                mWifiServiceImpl.getNetworkSuggestionUserApprovalStatus(TEST_PACKAGE_NAME));
        mLooper.stopAutoDispatchAndIgnoreExceptions();
        verify(mWifiNetworkSuggestionsManager)
                .getNetworkSuggestionUserApprovalStatus(anyInt(), eq(TEST_PACKAGE_NAME));
    }
}
