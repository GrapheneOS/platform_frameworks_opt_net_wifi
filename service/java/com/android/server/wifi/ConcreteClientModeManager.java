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

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.DhcpResultsParcelable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.WorkSource;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.util.ActionListenerWrapper;
import com.android.server.wifi.util.StateMachineObituary;
import com.android.server.wifi.util.WifiHandler;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manage WiFi in Client Mode where we connect to configured networks and in Scan Only Mode where
 * we do not connect to configured networks but do perform scanning.
 *
 * An instance of this class is active to manage each client interface. This is in contrast to
 * {@link DefaultClientModeManager} which handles calls when no client interfaces are active.
 *
 * This class will dynamically instantiate {@link ClientModeImpl} when it enters client mode, and
 * tear it down when it exits client mode. No instance of ClientModeImpl will be active in
 * scan-only mode, instead {@link ScanOnlyModeImpl} will be used to respond to calls.
 *
 * <pre>
 *                                           ActiveModeWarden
 *                                      /                        \
 *                                     /                          \
 *                        ConcreteClientModeManager         DefaultClientModeManager
 *                      (Client Mode + Scan Only Mode)            (Wifi off)
 *                             /            \
 *                           /               \
 *                     ClientModeImpl       ScanOnlyModeImpl
 * </pre>
 */
public class ConcreteClientModeManager implements ClientModeManager {
    private static final String TAG = "WifiClientModeManager";

    private final ClientModeStateMachine mStateMachine;

    private final Context mContext;
    private final Clock mClock;
    private final WifiNative mWifiNative;
    private final WifiMetrics mWifiMetrics;
    private final WakeupController mWakeupController;
    private final Listener mModeListener;
    private final WifiInjector mWifiInjector;
    private final SelfRecovery mSelfRecovery;
    private final WifiGlobals mWifiGlobals;
    private final ScanOnlyModeImpl mScanOnlyModeImpl;
    private final Graveyard mGraveyard = new Graveyard();

    private String mClientInterfaceName;
    private boolean mIfaceIsUp = false;
    private DeferStopHandler mDeferStopHandler;
    @Nullable
    private ClientRole mRole = null;
    @Nullable
    private ClientRole mTargetRole = null;
    private boolean mVerboseLoggingEnabled = false;
    /** Cache to store the external scorer for primary and secondary client mode impl. */
    @Nullable private Pair<IBinder, IWifiConnectedNetworkScorer> mScorer;
    private int mActiveSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    /**
     * mClientModeImpl is only non-null when in {@link ClientModeStateMachine.ConnectModeState} -
     * it will be null in all other states
     */
    @Nullable
    private ClientModeImpl mClientModeImpl = null;

    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     * {@link WifiManager#WIFI_STATE_DISABLING},
     * {@link WifiManager#WIFI_STATE_ENABLED},
     * {@link WifiManager#WIFI_STATE_ENABLING},
     * {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    ConcreteClientModeManager(Context context, @NonNull Looper looper, Clock clock,
            WifiNative wifiNative, Listener listener, WifiMetrics wifiMetrics,
            WakeupController wakeupController, WifiInjector wifiInjector,
            SelfRecovery selfRecovery, WifiGlobals wifiGlobals,
            ScanOnlyModeImpl scanOnlyModeImpl) {
        mContext = context;
        mClock = clock;
        mWifiNative = wifiNative;
        mModeListener = listener;
        mWifiMetrics = wifiMetrics;
        mWakeupController = wakeupController;
        mWifiInjector = wifiInjector;
        mStateMachine = new ClientModeStateMachine(looper);
        mDeferStopHandler = new DeferStopHandler(looper);
        mSelfRecovery = selfRecovery;
        mWifiGlobals = wifiGlobals;
        mScanOnlyModeImpl = scanOnlyModeImpl;
    }

    private String getTag() {
        return TAG + "[" + (mClientInterfaceName == null ? "unknown" : mClientInterfaceName) + "]";
    }

    /**
     * Start client mode.
     */
    @Override
    public void start(@NonNull WorkSource requestorWs) {
        mTargetRole = ROLE_CLIENT_SCAN_ONLY;
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START, requestorWs);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    @Override
    public void stop() {
        Log.d(getTag(), " currentstate: " + getCurrentStateName());
        mTargetRole = null;
        if (mIfaceIsUp) {
            updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                    WifiManager.WIFI_STATE_ENABLED);
        } else {
            updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                    WifiManager.WIFI_STATE_ENABLING);
        }
        mDeferStopHandler.start(getWifiOffDeferringTimeMs());
    }

    private class DeferStopHandler extends WifiHandler {
        private boolean mIsDeferring = false;
        private ImsMmTelManager mImsMmTelManager = null;
        private Looper mLooper = null;
        private final Runnable mRunnable = () -> continueToStopWifi();
        private int mMaximumDeferringTimeMillis = 0;
        private long mDeferringStartTimeMillis = 0;
        private NetworkRequest mImsRequest = null;
        private ConnectivityManager mConnectivityManager = null;

        private RegistrationManager.RegistrationCallback mImsRegistrationCallback =
                new RegistrationManager.RegistrationCallback() {
                    @Override
                    public void onRegistered(int imsRadioTech) {
                        Log.d(getTag(), "on IMS registered on type " + imsRadioTech);
                        if (!mIsDeferring) return;

                        if (imsRadioTech != AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
                            continueToStopWifi();
                        }
                    }

                    @Override
                    public void onUnregistered(ImsReasonInfo imsReasonInfo) {
                        Log.d(getTag(), "on IMS unregistered");
                        // Wait for onLost in NetworkCallback
                    }
                };

        private NetworkCallback mImsNetworkCallback = new NetworkCallback() {
            private int mRegisteredImsNetworkCount = 0;

            @Override
            public void onAvailable(Network network) {
                synchronized (this) {
                    Log.d(getTag(), "IMS network available: " + network);
                    mRegisteredImsNetworkCount++;
                }
            }

            @Override
            public void onLost(Network network) {
                synchronized (this) {
                    Log.d(getTag(), "IMS network lost: " + network
                            + " ,isDeferring: " + mIsDeferring
                            + " ,registered IMS network count: " + mRegisteredImsNetworkCount);
                    mRegisteredImsNetworkCount--;
                    if (mIsDeferring && mRegisteredImsNetworkCount <= 0) {
                        mRegisteredImsNetworkCount = 0;
                        // Add delay for targets where IMS PDN down at modem takes additional delay.
                        int delay = mContext.getResources()
                                .getInteger(R.integer.config_wifiDelayDisconnectOnImsLostMs);
                        if (delay == 0 || !postDelayed(mRunnable, delay)) {
                            continueToStopWifi();
                        }
                    }
                }
            }
        };

        DeferStopHandler(Looper looper) {
            super(TAG, looper);
            mLooper = looper;
        }

        public void start(int delayMs) {
            if (mIsDeferring) return;

            mMaximumDeferringTimeMillis = delayMs;
            mDeferringStartTimeMillis = mClock.getElapsedSinceBootMillis();
            // Most cases don't need delay, check it first to avoid unnecessary work.
            if (delayMs == 0) {
                continueToStopWifi();
                return;
            }

            mImsMmTelManager = ImsMmTelManager.createForSubscriptionId(mActiveSubId);
            if (mImsMmTelManager == null || !postDelayed(mRunnable, delayMs)) {
                // if no delay or failed to add runnable, stop Wifi immediately.
                continueToStopWifi();
                return;
            }

            mIsDeferring = true;
            Log.d(getTag(), "Start DeferWifiOff handler with deferring time "
                    + delayMs + " ms for subId: " + mActiveSubId);
            try {
                mImsMmTelManager.registerImsRegistrationCallback(
                        new HandlerExecutor(new Handler(mLooper)),
                        mImsRegistrationCallback);
            } catch (RuntimeException | ImsException e) {
                Log.e(getTag(), "registerImsRegistrationCallback failed", e);
                continueToStopWifi();
                return;
            }

            mImsRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();

            mConnectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

            mConnectivityManager.registerNetworkCallback(mImsRequest, mImsNetworkCallback,
                    new Handler(mLooper));
        }

        private void continueToStopWifi() {
            Log.d(getTag(), "The target role " + mTargetRole);

            int deferringDurationMillis =
                    (int) (mClock.getElapsedSinceBootMillis() - mDeferringStartTimeMillis);
            boolean isTimedOut = mMaximumDeferringTimeMillis > 0
                    && deferringDurationMillis >= mMaximumDeferringTimeMillis;
            if (mTargetRole == null) {
                Log.d(getTag(), "Continue to stop wifi");
                mStateMachine.captureObituaryAndQuitNow();
                mWifiMetrics.noteWifiOff(mIsDeferring, isTimedOut, deferringDurationMillis);
            } else if (mTargetRole == ROLE_CLIENT_SCAN_ONLY) {
                if (!mWifiNative.switchClientInterfaceToScanMode(mClientInterfaceName)) {
                    mModeListener.onStartFailure();
                } else {
                    mStateMachine.sendMessage(
                            ClientModeStateMachine.CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE);
                    mWifiMetrics.noteWifiOff(mIsDeferring, isTimedOut, deferringDurationMillis);
                }
            } else {
                updateConnectModeState(WifiManager.WIFI_STATE_ENABLED,
                        WifiManager.WIFI_STATE_DISABLING);
            }

            if (!mIsDeferring) return;

            Log.d(getTag(), "Stop DeferWifiOff handler.");
            removeCallbacks(mRunnable);
            if (mImsMmTelManager != null) {
                try {
                    mImsMmTelManager.unregisterImsRegistrationCallback(mImsRegistrationCallback);
                } catch (RuntimeException e) {
                    Log.e(getTag(), "unregisterImsRegistrationCallback failed", e);
                }
            }

            if (mConnectivityManager != null) {
                mConnectivityManager.unregisterNetworkCallback(mImsNetworkCallback);
            }

            mIsDeferring = false;
        }
    }

    /**
     * Get deferring time before turning off WiFi.
     */
    private int getWifiOffDeferringTimeMs() {
        SubscriptionManager subscriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            Log.d(getTag(), "SubscriptionManager not found");
            return 0;
        }

        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null) {
            Log.d(getTag(), "Active SubscriptionInfo list not found");
            return 0;
        }

        // Get the maximum delay for the active subscription latched on IWLAN.
        int maxDelay = 0;
        for (SubscriptionInfo subInfo : subInfoList) {
            int curDelay = getWifiOffDeferringTimeMs(subInfo.getSubscriptionId());
            if (curDelay > maxDelay) {
                maxDelay = curDelay;
                mActiveSubId = subInfo.getSubscriptionId();
            }
        }
        return maxDelay;
    }

    private int getWifiOffDeferringTimeMs(int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.d(getTag(), "Invalid Subscription ID: " + subId);
            return 0;
        }

        ImsMmTelManager imsMmTelManager = ImsMmTelManager.createForSubscriptionId(subId);
        // If no wifi calling, no delay
        try {
            if (!imsMmTelManager.isAvailable(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN)) {
                Log.d(getTag(), "IMS not registered over IWLAN for subId: " + subId);
                return 0;
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "IMS Manager is not available.", ex);
            return 0;
        }

        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle config = configManager.getConfigForSubId(subId);
        return (config != null)
                ? config.getInt(CarrierConfigManager.Ims.KEY_WIFI_OFF_DEFERRING_TIME_MILLIS_INT)
                : 0;
    }

    @Override
    public ClientRole getRole() {
        return mRole;
    }

    /** Set the role of this ClientModeManager */
    public void setRole(ClientRole role) {
        if (role == ROLE_CLIENT_SCAN_ONLY) {
            mTargetRole = role;
            // Switch client mode manager to scan only mode.
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_SCAN_ONLY_MODE);
        } else if (role instanceof ClientConnectivityRole) {
            mTargetRole = role;
            // Switch client mode manager to connect mode.
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_CONNECT_MODE, role);
        }
    }

    @Override
    public String getInterfaceName() {
        return mClientInterfaceName;
    }

    /**
     * Keep stopped {@link ClientModeImpl} instances so that they can be dumped to aid debugging.
     *
     * TODO(b/160283853): Find a smarter way to evict old ClientModeImpls
     */
    private static class Graveyard {
        private static final int INSTANCES_TO_KEEP = 3;

        private final ArrayDeque<ClientModeImpl> mClientModeImpls = new ArrayDeque<>();

        /**
         * Add this stopped {@link ClientModeImpl} to the graveyard, and evict the oldest
         * ClientModeImpl if the graveyard is full.
         */
        void inter(ClientModeImpl clientModeImpl) {
            if (mClientModeImpls.size() == INSTANCES_TO_KEEP) {
                mClientModeImpls.removeFirst();
            }
            mClientModeImpls.addLast(clientModeImpl);
        }

        /** Dump the contents of the graveyard. */
        void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("Dump of ConcreteClientModeManager.Graveyard");
            pw.println("Stopped ClientModeImpls: " + mClientModeImpls.size() + " total");
            int i = 0;
            for (ClientModeImpl clientModeImpl : mClientModeImpls) {
                pw.println("Dump of stopped ClientModeImpl " + i);
                clientModeImpl.dump(fd, pw, args);
                i++;
            }
            pw.println();
        }
    }

    /**
     * Dump info about this ClientMode manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of ClientModeManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mTargetRole: " + mTargetRole);
        pw.println("mClientInterfaceName: " + mClientInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        mStateMachine.dump(fd, pw, args);
        pw.println();
        pw.println("Wi-Fi is " + syncGetWifiStateByName());
        if (mClientModeImpl == null) {
            pw.println("No active ClientModeImpl instance");
        } else {
            mClientModeImpl.dump(fd, pw, args);
        }
        mGraveyard.dump(fd, pw, args);
        pw.println();
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update Wifi state and send the broadcast.
     *
     * @param newState     new Wifi state
     * @param currentState current wifi state
     */
    private void updateConnectModeState(int newState, int currentState) {
        if (newState == WifiManager.WIFI_STATE_UNKNOWN) {
            // do not need to broadcast failure to system
            return;
        }
        if (mRole != ROLE_CLIENT_PRIMARY) {
            // do not raise public broadcast unless this is the primary client mode manager
            return;
        }

        setWifiStateForApiCalls(newState);

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, currentState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void setWifiStateForApiCalls(int newState) {
        switch (newState) {
            case WIFI_STATE_DISABLING:
            case WIFI_STATE_DISABLED:
            case WIFI_STATE_ENABLING:
            case WIFI_STATE_ENABLED:
            case WIFI_STATE_UNKNOWN:
                if (mVerboseLoggingEnabled) {
                    Log.d(getTag(), "setting wifi state to: " + newState);
                }
                mWifiState.set(newState);
                break;
            default:
                Log.d(getTag(), "attempted to set an invalid state: " + newState);
                break;
        }
    }

    private String syncGetWifiStateByName() {
        switch (mWifiState.get()) {
            case WIFI_STATE_DISABLING:
                return "disabling";
            case WIFI_STATE_DISABLED:
                return "disabled";
            case WIFI_STATE_ENABLING:
                return "enabling";
            case WIFI_STATE_ENABLED:
                return "enabled";
            case WIFI_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_SWITCH_TO_SCAN_ONLY_MODE = 1;
        public static final int CMD_SWITCH_TO_CONNECT_MODE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_INTERFACE_DESTROYED = 4;
        public static final int CMD_INTERFACE_DOWN = 5;
        public static final int CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE = 6;
        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private final State mScanOnlyModeState = new ScanOnlyModeState();
        private final State mConnectModeState = new ConnectModeState();

        @Nullable
        private StateMachineObituary mObituary = null;

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    Log.d(getTag(), "STA iface " + ifaceName + " was destroyed, "
                            + "stopping client mode");

                    // we must immediately clean up state in ClientModeImpl to unregister
                    // all client mode related objects
                    // Note: onDestroyed is only called from the main Wifi thread
                    if (mClientModeImpl == null) {
                        Log.w(getTag(), "Received mWifiNativeInterfaceCallback.onDestroyed "
                                + "callback when no ClientModeImpl instance is active.");
                    } else {
                        mClientModeImpl.handleIfaceDestroyed();
                    }

                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
            addState(mStartedState);
                addState(mScanOnlyModeState, mStartedState);
                addState(mConnectModeState, mStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        void captureObituaryAndQuitNow() {
            // capture StateMachine LogRecs since we will lose them after we call quitNow()
            // This is used for debugging.
            mObituary = new StateMachineObituary(this);

            quitNow();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mObituary == null) {
                // StateMachine hasn't quit yet, dump `this` via StateMachineObituary's dump()
                // method for consistency with `else` branch.
                new StateMachineObituary(this).dump(fd, pw, args);
            } else {
                // StateMachine has quit and cleared all LogRecs.
                // Get them from the obituary instead.
                mObituary.dump(fd, pw, args);
            }
        }

        private void setRoleInternalAndInvokeCallback(ClientRole newRole) {
            if (newRole == mRole) return;
            if (mRole == null) {
                Log.v(getTag(), "ClientModeManager started in role: " + newRole);
                mRole = newRole;
                mModeListener.onStarted();
            } else {
                Log.v(getTag(), "ClientModeManager role changed: " + newRole);
                mRole = newRole;
                mModeListener.onRoleChanged();
            }
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                Log.d(getTag(), "entering IdleState");
                mClientInterfaceName = null;
                mIfaceIsUp = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        // Always start in scan mode first.
                        WorkSource requestorWs = (WorkSource) message.obj;
                        mClientInterfaceName = mWifiNative.setupInterfaceForClientInScanMode(
                                mWifiNativeInterfaceCallback, requestorWs);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(getTag(), "Failed to create ClientInterface. Sit in Idle");
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mScanOnlyModeState);
                        break;
                    default:
                        Log.d(getTag(), "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (!isUp) {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(getTag(), "interface down!");
                    mStateMachine.sendMessage(CMD_INTERFACE_DOWN);
                }
            }

            @Override
            public void enter() {
                Log.d(getTag(), "entering StartedState");
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        // could be any one of possible connect mode roles.
                        setRoleInternalAndInvokeCallback((ClientRole) message.obj);
                        updateConnectModeState(WifiManager.WIFI_STATE_ENABLING,
                                WifiManager.WIFI_STATE_DISABLED);
                        if (!mWifiNative.switchClientInterfaceToConnectivityMode(
                                mClientInterfaceName)) {
                            updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                    WifiManager.WIFI_STATE_ENABLING);
                            updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mConnectModeState);
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        mDeferStopHandler.start(getWifiOffDeferringTimeMs());
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE:
                        transitionTo(mScanOnlyModeState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.e(getTag(), "Detected an interface down, reporting failure to "
                                + "SelfRecovery");
                        mSelfRecovery.trigger(SelfRecovery.REASON_STA_IFACE_DOWN);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(getTag(), "interface destroyed - client mode stopping");
                        mClientInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and update wifi state.
             */
            @Override
            public void exit() {
                if (mClientInterfaceName != null) {
                    mWifiNative.teardownInterface(mClientInterfaceName);
                    mClientInterfaceName = null;
                    mIfaceIsUp = false;
                }

                // once we leave started, nothing else to do...  stop the state machine
                mRole = null;
                mStateMachine.captureObituaryAndQuitNow();
                mModeListener.onStopped();
            }
        }

        private class ScanOnlyModeState extends State {
            @Override
            public void enter() {
                Log.d(getTag(), "entering ScanOnlyModeState");
                setRoleInternalAndInvokeCallback(ROLE_CLIENT_SCAN_ONLY);

                mWakeupController.start();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        // Already in scan only mode, ignore this command.
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                mWakeupController.stop();
            }
        }

        private class ConnectModeState extends State {
            @Override
            public void enter() {
                Log.d(getTag(), "entering ConnectModeState, starting ClientModeImpl");
                if (mClientInterfaceName == null) {
                    Log.e(getTag(), "Supposed to start ClientModeImpl, but iface is null!");
                } else {
                    if (mClientModeImpl != null) {
                        Log.e(getTag(), "ConnectModeState.enter(): mClientModeImpl is already "
                                + "instantiated?!");
                    }
                    mClientModeImpl = mWifiInjector.makeClientModeImpl(
                            mClientInterfaceName, ConcreteClientModeManager.this);
                    mClientModeImpl.enableVerboseLogging(mVerboseLoggingEnabled);
                    if (mScorer != null) {
                        mClientModeImpl.setWifiConnectedNetworkScorer(
                                mScorer.first, mScorer.second);
                    }
                }
                updateConnectModeState(WifiManager.WIFI_STATE_ENABLED,
                        WifiManager.WIFI_STATE_ENABLING);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        // Already in connect mode, only switching the connectivity roles.
                        setRoleInternalAndInvokeCallback((ClientRole) message.obj);
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DOWN:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_UNKNOWN);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        if (isUp == mIfaceIsUp) {
                            break;  // no change
                        }
                        if (!isUp) {
                            if (!mWifiGlobals.isConnectedMacRandomizationEnabled()) {
                                // Handle the error case where our underlying interface went down if
                                // we do not have mac randomization enabled (b/72459123).
                                // if the interface goes down we should exit and go back to idle
                                // state.
                                updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                        WifiManager.WIFI_STATE_ENABLED);
                            } else {
                                return HANDLED; // For MAC randomization, ignore...
                            }
                        }
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DESTROYED:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                        WifiManager.WIFI_STATE_DISABLING);

                if (mClientModeImpl == null) {
                    Log.w(getTag(), "ConnectModeState.exit(): mClientModeImpl is already null?!");
                } else {
                    Log.d(getTag(), "Stopping ClientModeImpl");
                    mClientModeImpl.stop();
                    mGraveyard.inter(mClientModeImpl);
                    mClientModeImpl = null;
                }
            }
        }
    }

    @Override
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    @NonNull
    private ClientMode getClientMode() {
        if (mClientModeImpl == null) {
            return mScanOnlyModeImpl;
        } else {
            return mClientModeImpl;
        }
    }

    /*
     * Note: These are simple wrappers over methods to {@link ClientModeImpl}.
     */

    @Override
    public void connectNetwork(NetworkUpdateResult result, ActionListenerWrapper wrapper,
            int callingUid) {
        getClientMode().connectNetwork(result, wrapper, callingUid);
    }

    @Override
    public void saveNetwork(NetworkUpdateResult result, ActionListenerWrapper wrapper,
            int callingUid) {
        getClientMode().saveNetwork(result, wrapper, callingUid);
    }

    @Override
    public void disconnect() {
        getClientMode().disconnect();
    }

    @Override
    public void reconnect(WorkSource ws) {
        getClientMode().reconnect(ws);
    }

    @Override
    public void reassociate() {
        getClientMode().reassociate();
    }

    @Override
    public void startConnectToNetwork(int networkId, int uid, String bssid) {
        getClientMode().startConnectToNetwork(networkId, uid, bssid);
    }

    @Override
    public void startRoamToNetwork(int networkId, ScanResult scanResult) {
        getClientMode().startRoamToNetwork(networkId, scanResult);
    }

    @Override
    public boolean setWifiConnectedNetworkScorer(
            IBinder binder, IWifiConnectedNetworkScorer scorer) {
        mScorer = Pair.create(binder, scorer);
        return getClientMode().setWifiConnectedNetworkScorer(binder, scorer);
    }

    @Override
    public void clearWifiConnectedNetworkScorer() {
        mScorer = null;
        getClientMode().clearWifiConnectedNetworkScorer();
    }

    @Override
    public void resetSimAuthNetworks(@ClientModeImpl.ResetSimReason int resetReason) {
        getClientMode().resetSimAuthNetworks(resetReason);
    }

    @Override
    public void onBluetoothConnectionStateChanged() {
        getClientMode().onBluetoothConnectionStateChanged();
    }

    @Override
    public WifiInfo syncRequestConnectionInfo() {
        return getClientMode().syncRequestConnectionInfo();
    }

    @Override
    public boolean syncQueryPasspointIcon(long bssid, String fileName) {
        return getClientMode().syncQueryPasspointIcon(bssid, fileName);
    }

    @Override
    public Network syncGetCurrentNetwork() {
        return getClientMode().syncGetCurrentNetwork();
    }

    @Override
    public DhcpResultsParcelable syncGetDhcpResultsParcelable() {
        return getClientMode().syncGetDhcpResultsParcelable();
    }

    @Override
    public long syncGetSupportedFeatures() {
        return getClientMode().syncGetSupportedFeatures();
    }

    @Override
    public boolean syncStartSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        return getClientMode().syncStartSubscriptionProvisioning(
                callingUid, provider, callback);
    }

    @Override
    public boolean isWifiStandardSupported(@WifiAnnotations.WifiStandard int standard) {
        return getClientMode().isWifiStandardSupported(standard);
    }

    @Override
    public void enableTdls(String remoteMacAddress, boolean enable) {
        getClientMode().enableTdls(remoteMacAddress, enable);
    }

    @Override
    public void dumpIpClient(FileDescriptor fd, PrintWriter pw, String[] args) {
        getClientMode().dumpIpClient(fd, pw, args);
    }

    @Override
    public void dumpWifiScoreReport(FileDescriptor fd, PrintWriter pw, String[] args) {
        getClientMode().dumpWifiScoreReport(fd, pw, args);
    }

    @Override
    public void updateLinkLayerStatsRssiAndScoreReport() {
        getClientMode().updateLinkLayerStatsRssiAndScoreReport();
    }

    @Override
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
        getClientMode().enableVerboseLogging(verbose);
    }

    @Override
    public String getFactoryMacAddress() {
        return getClientMode().getFactoryMacAddress();
    }

    @Override
    public WifiConfiguration getConnectedWifiConfiguration() {
        return getClientMode().getConnectedWifiConfiguration();
    }

    @Override
    public WifiConfiguration getConnectingWifiConfiguration() {
        return getClientMode().getConnectingWifiConfiguration();
    }

    @Override
    public String getConnectedBssid() {
        return getClientMode().getConnectedBssid();
    }

    @Override
    public String getConnectingBssid() {
        return getClientMode().getConnectingBssid();
    }

    @Override
    public WifiLinkLayerStats getWifiLinkLayerStats() {
        return getClientMode().getWifiLinkLayerStats();
    }

    @Override
    public boolean setPowerSave(boolean ps) {
        return getClientMode().setPowerSave(ps);
    }

    @Override
    public boolean setLowLatencyMode(boolean enabled) {
        return getClientMode().setLowLatencyMode(enabled);
    }

    @Override
    public WifiMulticastLockManager.FilterController getMcastLockManagerFilterController() {
        return getClientMode().getMcastLockManagerFilterController();
    }

    @Override
    public boolean isConnected() {
        return getClientMode().isConnected();
    }

    @Override
    public boolean isConnecting() {
        return mClientModeImpl.isConnecting();
    }

    @Override
    public boolean isRoaming() {
        return mClientModeImpl.isRoaming();
    }

    @Override
    public boolean isDisconnected() {
        return getClientMode().isDisconnected();
    }

    @Override
    public boolean isSupplicantTransientState() {
        return getClientMode().isSupplicantTransientState();
    }

    @Override
    public void probeLink(WifiNl80211Manager.SendMgmtFrameCallback callback, int mcs) {
        getClientMode().probeLink(callback, mcs);
    }

    @Override
    public void sendMessageToClientModeImpl(Message msg) {
        getClientMode().sendMessageToClientModeImpl(msg);
    }
}
