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

import static android.net.wifi.WifiManager.SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_UNSUPPORTED_CONFIGURATION;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under the ClientModeImpl handler thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Timeout";

    private final WifiContext mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;

    @VisibleForTesting
    SoftApNotifier mSoftApNotifier;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener mModeListener;
    private final WifiManager.SoftApCallback mSoftApCallback;

    private String mApInterfaceName;
    private boolean mIfaceIsUp;
    private boolean mIfaceIsDestroyed;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    private boolean mIsUnsetBssid;

    @NonNull
    private SoftApModeConfiguration mApConfig;

    @NonNull
    private SoftApInfo mCurrentSoftApInfo = new SoftApInfo();

    @NonNull
    private SoftApCapability mCurrentSoftApCapability;

    private List<WifiClient> mConnectedClients = new ArrayList<>();
    private boolean mTimeoutEnabled = false;

    private String mStartTimestamp;

    private long mDefaultShutDownTimeoutMills;

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private WifiDiagnostics mWifiDiagnostics;

    @Nullable
    private SoftApRole mRole = null;

    private boolean mEverReportMetricsForMaxClient = false;

    @NonNull
    private Set<MacAddress> mBlockedClientList = new HashSet<>();

    @NonNull
    private Set<MacAddress> mAllowedClientList = new HashSet<>();

    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {
        @Override
        public void onFailure() {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_FAILURE);
        }

        @Override
        public void onInfoChanged(String apIfaceInstance, int frequency,
                @WifiAnnotations.Bandwidth int bandwidth,
                @WifiAnnotations.WifiStandard int generation,
                MacAddress apIfaceInstanceMacAddress) {
            SoftApInfo apInfo = new SoftApInfo();
            apInfo.setFrequency(frequency);
            apInfo.setBandwidth(bandwidth);
            apInfo.setWifiStandard(generation);
            if (apIfaceInstanceMacAddress != null) {
                apInfo.setBssid(apIfaceInstanceMacAddress);
            }
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_AP_INFO_CHANGED, 0, 0, apInfo);
        }

        @Override
        public void onConnectedClientsChanged(String apIfaceInstance, MacAddress clientAddress,
                boolean isConnected) {
            if (clientAddress != null) {
                mStateMachine.sendMessage(SoftApStateMachine.CMD_ASSOCIATED_STATIONS_CHANGED,
                        isConnected ? 1 : 0, 0, clientAddress);
            } else {
                Log.e(getTag(), "onConnectedClientsChanged: Invalid type returned");
            }
        }
    };

    public SoftApManager(@NonNull WifiContext context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull Listener listener,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics,
                         @NonNull WifiDiagnostics wifiDiagnostics) {
        mContext = context;
        mFrameworkFacade = framework;
        mSoftApNotifier = new SoftApNotifier(mContext, mFrameworkFacade);
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mModeListener = listener;
        mSoftApCallback = callback;
        mWifiApConfigStore = wifiApConfigStore;
        SoftApConfiguration softApConfig = apConfig.getSoftApConfiguration();
        mCurrentSoftApCapability = apConfig.getCapability();
        // null is a valid input and means we use the user-configured tethering settings.
        if (softApConfig == null) {
            softApConfig = mWifiApConfigStore.getApConfiguration();
            // may still be null if we fail to load the default config
        }
        if (softApConfig != null) {
            mIsUnsetBssid = softApConfig.getBssid() == null;
            softApConfig = mWifiApConfigStore.randomizeBssidIfUnset(mContext, softApConfig);
        }
        mApConfig = new SoftApModeConfiguration(apConfig.getTargetMode(),
                softApConfig, mCurrentSoftApCapability);
        mWifiMetrics = wifiMetrics;
        mWifiDiagnostics = wifiDiagnostics;
        mStateMachine = new SoftApStateMachine(looper);
        if (softApConfig != null) {
            mBlockedClientList = new HashSet<>(softApConfig.getBlockedClientList());
            mAllowedClientList = new HashSet<>(softApConfig.getAllowedClientList());
            mTimeoutEnabled = softApConfig.isAutoShutdownEnabled();
        }
        mDefaultShutDownTimeoutMills = mContext.getResources().getInteger(
                R.integer.config_wifiFrameworkSoftApShutDownTimeoutMilliseconds);
    }

    private String getTag() {
        return TAG + "[" + (mApInterfaceName == null ? "unknown" : mApInterfaceName) + "]";
    }

    /**
     * Start soft AP, as configured in the constructor.
     */
    @Override
    public void start(@NonNull WorkSource requestorWs) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, requestorWs);
    }

    /**
     * Stop soft AP.
     */
    @Override
    public void stop() {
        Log.d(getTag(), " currentstate: " + getCurrentStateName());
        mStateMachine.sendMessage(SoftApStateMachine.CMD_STOP);
    }

    @Override
    public SoftApRole getRole() {
        return mRole;
    }

    /** Set the role of this SoftApManager */
    public void setRole(SoftApRole role) {
        // softap does not allow in-place switching of roles.
        Preconditions.checkState(mRole == null);
        mRole = role;
    }

    @Override
    public String getInterfaceName() {
        return mApInterfaceName;
    }

    /**
     * Update AP capability. Called when carrier config or device resouce config changed.
     *
     * @param capability new AP capability.
     */
    public void updateCapability(@NonNull SoftApCapability capability) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CAPABILITY, capability);
    }

    /**
     * Update AP configuration. Called when setting update config via
     * {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
     *
     * @param config new AP config.
     */
    public void updateConfiguration(@NonNull SoftApConfiguration config) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CONFIG, config);
    }

    /**
     * Dump info about this softap manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of SoftApManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mSoftApCountryCode: " + mCountryCode);
        pw.println("mApConfig.targetMode: " + mApConfig.getTargetMode());
        SoftApConfiguration softApConfig = mApConfig.getSoftApConfiguration();
        pw.println("mApConfig.SoftApConfiguration.SSID: " + softApConfig.getSsid());
        pw.println("mApConfig.SoftApConfiguration.mBand: " + softApConfig.getBand());
        pw.println("mApConfig.SoftApConfiguration.hiddenSSID: " + softApConfig.isHiddenSsid());
        pw.println("mConnectedClients.size(): " + mConnectedClients.size());
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mCurrentSoftApInfo " + mCurrentSoftApInfo);
        pw.println("mStartTimestamp: " + mStartTimestamp);
        mStateMachine.dump(fd, pw, args);
    }

    @Override
    public void enableVerboseLogging(boolean verbose) { /* unused */ }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update AP state.
     *
     * @param newState     new AP state
     * @param currentState current AP state
     * @param reason       Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mSoftApCallback.onStateChanged(newState, reason);

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mApInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mApConfig.getTargetMode());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int setMacAddress() {
        MacAddress mac = mApConfig.getSoftApConfiguration().getBssid();

        if (mac == null) {
            // If no BSSID is explicitly requested, (re-)configure the factory MAC address. Some
            // drivers may not support setting the MAC at all, so fail soft in this case.
            mac = mWifiNative.getApFactoryMacAddress(mApInterfaceName);
            if (mac == null) {
                Log.e(getTag(), "failed to get factory MAC address");
                return ERROR_GENERIC;
            }

            if (!mWifiNative.setApMacAddress(mApInterfaceName, mac)) {
                Log.w(getTag(), "failed to reset to factory MAC address; "
                        + "continuing with current MAC");
            }
        } else {
            if (mWifiNative.isApSetMacAddressSupported(mApInterfaceName)) {
                if (!mWifiNative.setApMacAddress(mApInterfaceName, mac)) {
                    Log.e(getTag(), "failed to set explicitly requested MAC address");
                    return ERROR_GENERIC;
                }
            } else if (!mIsUnsetBssid) {
                // If hardware does not support MAC address setter,
                // only report the error for non randomization.
                return ERROR_UNSUPPORTED_CONFIGURATION;
            }
        }

        return SUCCESS;
    }

    private int setCountryCode() {
        int band = mApConfig.getSoftApConfiguration().getBand();
        if (TextUtils.isEmpty(mCountryCode)) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Country code is mandatory for 5GHz band.
                Log.e(getTag(), "Invalid country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Absence of country code is not fatal for 2Ghz & Any band options.
            return SUCCESS;
        }

        if (!mWifiNative.setCountryCodeHal(
                mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Return an error if failed to set country code when AP is configured for
                // 5GHz band.
                Log.e(getTag(), "Failed to set country code, "
                        + "required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Failure to set country code is not fatal for other band options.
        }
        return SUCCESS;
    }

    /**
     * Start a soft AP instance as configured.
     *
     * @return integer result code
     */
    private int startSoftAp() {
        SoftApConfiguration config = mApConfig.getSoftApConfiguration();
        if (config == null || config.getSsid() == null) {
            Log.e(getTag(), "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }

        Log.d(getTag(), "band " + config.getBand() + " iface "
                + mApInterfaceName + " country " + mCountryCode);

        int result = setMacAddress();
        if (result != SUCCESS) {
            return result;
        }

        result = setCountryCode();
        if (result != SUCCESS) {
            return result;
        }

        // Make a copy of configuration for updating AP band and channel.
        SoftApConfiguration.Builder localConfigBuilder = new SoftApConfiguration.Builder(config);

        boolean acsEnabled = mCurrentSoftApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);

        result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mContext.getResources(), mCountryCode, localConfigBuilder, config,
                acsEnabled);
        if (result != SUCCESS) {
            Log.e(getTag(), "Failed to update AP band and channel");
            return result;
        }

        if (config.isHiddenSsid()) {
            Log.d(getTag(), "SoftAP is a hidden network");
        }

        if (!ApConfigUtil.checkSupportAllConfiguration(config, mCurrentSoftApCapability)) {
            Log.d(getTag(), "Unsupported Configuration detect! config = " + config);
            return ERROR_UNSUPPORTED_CONFIGURATION;
        }

        if (!mWifiNative.startSoftAp(mApInterfaceName,
                  localConfigBuilder.build(), mSoftApListener)) {
            Log.e(getTag(), "Soft AP start failed");
            return ERROR_GENERIC;
        }

        mWifiDiagnostics.startLogging(mApInterfaceName);
        mStartTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        Log.d(getTag(), "Soft AP is started ");

        return SUCCESS;
    }

    /**
     * Disconnect all connected clients on active softap interface(s).
     * This is usually done just before stopSoftAp().
     */
    private void disconnectAllClients() {
        for (WifiClient client : mConnectedClients) {
            mWifiNative.forceClientDisconnect(mApInterfaceName, client.getMacAddress(),
                    SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED);
        }
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        disconnectAllClients();
        mWifiDiagnostics.stopLogging(mApInterfaceName);
        mWifiNative.teardownInterface(mApInterfaceName);
        Log.d(getTag(), "Soft AP is stopped");
    }

    private boolean checkSoftApClient(SoftApConfiguration config, WifiClient newClient) {
        if (!mCurrentSoftApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
            return true;
        }

        if (mBlockedClientList.contains(newClient.getMacAddress())) {
            Log.d(getTag(), "Force disconnect for client: " + newClient + "in blocked list");
            mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            return false;
        }
        if (config.isClientControlByUserEnabled()
                && !mAllowedClientList.contains(newClient.getMacAddress())) {
            mSoftApCallback.onBlockedClientConnecting(newClient,
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            Log.d(getTag(), "Force disconnect for unauthorized client: " + newClient);
            mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            return false;
        }
        int maxConfig = mCurrentSoftApCapability.getMaxSupportedClients();
        if (config.getMaxNumberOfClients() > 0) {
            maxConfig = Math.min(maxConfig, config.getMaxNumberOfClients());
        }

        if (mConnectedClients.size() >= maxConfig) {
            Log.i(getTag(), "No more room for new client:" + newClient);
            mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            mSoftApCallback.onBlockedClientConnecting(newClient,
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            // Avoid report the max client blocked in the same settings.
            if (!mEverReportMetricsForMaxClient) {
                mWifiMetrics.noteSoftApClientBlocked(maxConfig);
                mEverReportMetricsForMaxClient = true;
            }
            return false;
        }
        return true;
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_AP_INFO_CHANGED = 9;
        public static final int CMD_UPDATE_CAPABILITY = 10;
        public static final int CMD_UPDATE_CONFIG = 11;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_STOP:
                        mStateMachine.quitNow();
                        break;
                    case CMD_START:
                        WorkSource requestorWs = (WorkSource) message.obj;
                        mApInterfaceName = mWifiNative.setupInterfaceForSoftApMode(
                                mWifiNativeInterfaceCallback, requestorWs);
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(getTag(), "setup failure when creating ap interface.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            mModeListener.onStartFailure();
                            break;
                        }
                        mSoftApNotifier.dismissSoftApShutDownTimeoutExpiredNotification();
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp();
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            } else if (result == ERROR_UNSUPPORTED_CONFIGURATION) {
                                failureReason = WifiManager
                                        .SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_ENABLING,
                                    failureReason);
                            stopSoftAp();
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        Log.d(getTag(), "Configuration changed to " + newConfig);
                        mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                newConfig, mCurrentSoftApCapability);
                        mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                        mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                        mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            private WakeupMessage mSoftApTimeoutMessage;

            private void scheduleTimeoutMessage() {
                if (!mTimeoutEnabled || mConnectedClients.size() != 0) {
                    cancelTimeoutMessage();
                    return;
                }
                long timeout = mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis();
                if (timeout == 0) {
                    timeout =  mDefaultShutDownTimeoutMills;
                }
                mSoftApTimeoutMessage.schedule(SystemClock.elapsedRealtime()
                        + timeout);
                Log.d(getTag(), "Timeout message scheduled, delay = "
                        + timeout);
            }

            private void cancelTimeoutMessage() {
                mSoftApTimeoutMessage.cancel();
                Log.d(getTag(), "Timeout message canceled");
            }

            /**
             * When configuration changed, it need to force some clients disconnect to match the
             * configuration.
             */
            private void updateClientConnection() {
                if (!mCurrentSoftApCapability.areFeaturesSupported(
                        SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
                    return;
                }
                final int maxAllowedClientsByHardwareAndCarrier =
                        mCurrentSoftApCapability.getMaxSupportedClients();
                final int userApConfigMaxClientCount =
                        mApConfig.getSoftApConfiguration().getMaxNumberOfClients();
                int finalMaxClientCount = maxAllowedClientsByHardwareAndCarrier;
                if (userApConfigMaxClientCount > 0) {
                    finalMaxClientCount = Math.min(userApConfigMaxClientCount,
                            maxAllowedClientsByHardwareAndCarrier);
                }
                int targetDisconnectClientNumber = mConnectedClients.size() - finalMaxClientCount;
                List<WifiClient> allowedConnectedList = new ArrayList<>();
                Iterator<WifiClient> iterator = mConnectedClients.iterator();
                while (iterator.hasNext()) {
                    WifiClient client = iterator.next();
                    if (mBlockedClientList.contains(client.getMacAddress())
                              || (mApConfig.getSoftApConfiguration().isClientControlByUserEnabled()
                              && !mAllowedClientList.contains(client.getMacAddress()))) {
                        Log.d(getTag(), "Force disconnect for not allowed client: " + client);
                        mWifiNative.forceClientDisconnect(
                                mApInterfaceName, client.getMacAddress(),
                                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
                        targetDisconnectClientNumber--;
                    } else {
                        allowedConnectedList.add(client);
                    }
                }

                if (targetDisconnectClientNumber > 0) {
                    Iterator<WifiClient> allowedClientIterator = allowedConnectedList.iterator();
                    while (allowedClientIterator.hasNext()) {
                        if (targetDisconnectClientNumber == 0) break;
                        WifiClient allowedClient = allowedClientIterator.next();
                        Log.d(getTag(), "Force disconnect for client due to no more room: "
                                + allowedClient);
                        mWifiNative.forceClientDisconnect(
                                mApInterfaceName, allowedClient.getMacAddress(),
                                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
                        targetDisconnectClientNumber--;
                    }
                }
            }

            /**
             * Set stations associated with this soft AP
             * @param client The station for which connection state changed.
             * @param isConnected True for the connection changed to connect, otherwise false.
             */
            private void updateConnectedClients(WifiClient client, boolean isConnected) {
                if (client == null) {
                    return;
                }

                int index = mConnectedClients.indexOf(client);
                if ((index != -1) == isConnected) {
                    Log.e(getTag(), "Drop client connection event, client "
                            + client + "isConnected: " + isConnected
                            + " , duplicate event or client is blocked");
                    return;
                }
                if (isConnected) {
                    boolean isAllow = checkSoftApClient(
                            mApConfig.getSoftApConfiguration(), client);
                    if (isAllow) {
                        mConnectedClients.add(client);
                    } else {
                        return;
                    }
                } else {
                    mConnectedClients.remove(index);
                }

                Log.d(getTag(), "The connected wifi stations have changed with count: "
                        + mConnectedClients.size() + ": " + mConnectedClients);

                if (mSoftApCallback != null) {
                    mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                } else {
                    Log.e(getTag(),
                            "SoftApCallback is null. Dropping ConnectedClientsChanged event."
                    );
                }

                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                        mConnectedClients.size(), mApConfig.getTargetMode());

                scheduleTimeoutMessage();
            }

            private void updateSoftApInfo(SoftApInfo apInfo) {
                Log.d(getTag(), "SoftApInfo update " + apInfo);

                if (apInfo.equals(mCurrentSoftApInfo)) {
                    return; // no change
                }

                mCurrentSoftApInfo = new SoftApInfo(apInfo);
                mSoftApCallback.onInfoChanged(mCurrentSoftApInfo);
                // ignore invalid freq and softap disable case for metrics
                if (apInfo.getFrequency() > 0
                        && apInfo.getBandwidth() != SoftApInfo.CHANNEL_WIDTH_INVALID) {
                    mWifiMetrics.addSoftApChannelSwitchedEvent(mCurrentSoftApInfo,
                            mApConfig.getTargetMode());
                    updateUserBandPreferenceViolationMetricsIfNeeded();
                }
            }

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }

                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(getTag(), "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mModeListener.onStarted();
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                    }
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mApConfig.getTargetMode(),
                        mDefaultShutDownTimeoutMills);
                if (isUp) {
                    mWifiMetrics.updateSoftApConfiguration(mApConfig.getSoftApConfiguration(),
                            mApConfig.getTargetMode());
                    mWifiMetrics.updateSoftApCapability(mCurrentSoftApCapability,
                            mApConfig.getTargetMode());
                }
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                onUpChanged(mWifiNative.isInterfaceUp(mApInterfaceName));

                Handler handler = mStateMachine.getHandler();
                mSoftApTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT);

                Log.d(getTag(), "Resetting connected clients on start");
                mConnectedClients.clear();
                mEverReportMetricsForMaxClient = false;
                scheduleTimeoutMessage();
            }

            @Override
            public void exit() {
                if (!mIfaceIsDestroyed) {
                    stopSoftAp();
                }

                Log.d(getTag(), "Resetting num stations on stop");
                if (mConnectedClients.size() != 0) {
                    mConnectedClients.clear();
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                    }
                    mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                            0, mApConfig.getTargetMode());
                }
                cancelTimeoutMessage();

                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mApConfig.getTargetMode(),
                        mDefaultShutDownTimeoutMills);
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);

                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                mRole = null;
                mStateMachine.quitNow();
                mModeListener.onStopped();
                updateSoftApInfo(new SoftApInfo());
            }

            private void updateUserBandPreferenceViolationMetricsIfNeeded() {
                int band = mApConfig.getSoftApConfiguration().getBand();
                boolean bandPreferenceViolated =
                        (ScanResult.is24GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_2GHZ))
                        || (ScanResult.is5GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_5GHZ))
                        || (ScanResult.is6GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_6GHZ));

                if (bandPreferenceViolated) {
                    Log.e(getTag(), "Channel does not satisfy user band preference: "
                            + mCurrentSoftApInfo.getFrequency());
                    mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_ASSOCIATED_STATIONS_CHANGED:
                        if (!(message.obj instanceof MacAddress)) {
                            Log.e(getTag(), "Invalid type returned for"
                                    + " CMD_ASSOCIATED_STATIONS_CHANGED");
                            break;
                        }
                        MacAddress madAddress = (MacAddress) message.obj;
                        boolean isConnected = (message.arg1 == 1);
                        WifiClient client = new WifiClient(madAddress);
                        Log.d(getTag(), "CMD_ASSOCIATED_STATIONS_CHANGED, Client: "
                                + madAddress.toString() + " isConnected: "
                                + isConnected);
                        updateConnectedClients(client, isConnected);
                        break;
                    case CMD_AP_INFO_CHANGED:
                        if (!(message.obj instanceof SoftApInfo)) {
                            Log.e(getTag(), "Invalid type returned for"
                                    + " CMD_AP_INFO_CHANGED");
                            break;
                        }
                        SoftApInfo apInfo = (SoftApInfo) message.obj;
                        if (apInfo.getFrequency() < 0) {
                            Log.e(getTag(), "Invalid ap channel frequency: "
                                    + apInfo.getFrequency());
                            break;
                        }
                        updateSoftApInfo(apInfo);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_STOP:
                        if (mIfaceIsUp) {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                    WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        } else {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                    WifiManager.WIFI_AP_STATE_ENABLING, 0);
                        }
                        transitionTo(mIdleState);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT:
                        if (!mTimeoutEnabled) {
                            Log.wtf(getTag(), "Timeout message received while timeout is disabled."
                                    + " Dropping.");
                            break;
                        }
                        if (mConnectedClients.size() != 0) {
                            Log.wtf(getTag(), "Timeout message received but has clients. "
                                    + "Dropping.");
                            break;
                        }
                        mSoftApNotifier.showSoftApShutDownTimeoutExpiredNotification();
                        Log.i(getTag(), "Timeout message received. Stopping soft AP.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(getTag(), "Interface was cleanly destroyed.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        mIfaceIsDestroyed = true;
                        transitionTo(mIdleState);
                        break;
                    case CMD_FAILURE:
                        Log.w(getTag(), "hostapd failure, stop and report failure");
                        /* fall through */
                    case CMD_INTERFACE_DOWN:
                        Log.w(getTag(), "interface error, stop and report failure");
                        updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                WifiManager.WIFI_AP_STATE_ENABLED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_FAILED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                            mWifiMetrics.updateSoftApCapability(mCurrentSoftApCapability,
                                    mApConfig.getTargetMode());
                            updateClientConnection();
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        SoftApConfiguration currentConfig = mApConfig.getSoftApConfiguration();
                        if (mIsUnsetBssid) {
                            // Current bssid is ramdonized because unset. Set back to null.
                            currentConfig = new SoftApConfiguration.Builder(currentConfig)
                                    .setBssid(null)
                                    .build();
                        }
                        if (!ApConfigUtil.checkConfigurationChangeNeedToRestart(
                                currentConfig, newConfig)) {
                            Log.d(getTag(), "Configuration changed to " + newConfig);
                            if (mApConfig.getSoftApConfiguration().getMaxNumberOfClients()
                                    != newConfig.getMaxNumberOfClients()) {
                                Log.d(getTag(), "Max Client changed, reset to record the metrics");
                                mEverReportMetricsForMaxClient = false;
                            }
                            boolean needRescheduleTimer =
                                    mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis()
                                    != newConfig.getShutdownTimeoutMillis()
                                    || mTimeoutEnabled != newConfig.isAutoShutdownEnabled();
                            mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                            mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                            mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                    newConfig, mCurrentSoftApCapability);
                            updateClientConnection();
                            if (needRescheduleTimer) {
                                cancelTimeoutMessage();
                                scheduleTimeoutMessage();
                            }
                            mWifiMetrics.updateSoftApConfiguration(
                                    mApConfig.getSoftApConfiguration(),
                                    mApConfig.getTargetMode());
                        } else {
                            Log.d(getTag(), "Ignore the config: " + newConfig
                                    + " update since it requires restart");
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
