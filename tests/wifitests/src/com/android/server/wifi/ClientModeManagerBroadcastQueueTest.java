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

import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_PRIMARY;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_TRANSIENT;

import static org.mockito.Mockito.*;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.ActiveModeWarden.ModeChangeCallback;
import com.android.server.wifi.ActiveModeWarden.PrimaryClientModeManagerChangedCallback;
import com.android.server.wifi.ClientModeManagerBroadcastQueue.QueuedBroadcast;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link ClientModeManagerBroadcastQueue}. */
@SmallTest
public class ClientModeManagerBroadcastQueueTest extends WifiBaseTest {

    private ClientModeManagerBroadcastQueue mBroadcastQueue;

    @Mock private ActiveModeWarden mActiveModeWarden;
    @Mock private ConcreteClientModeManager mPrimaryManager;
    @Mock private ConcreteClientModeManager mSecondaryManager;
    @Mock private QueuedBroadcast mQueuedBroadcast1;
    @Mock private QueuedBroadcast mQueuedBroadcast2;
    @Mock private QueuedBroadcast mQueuedBroadcast3;

    @Captor private ArgumentCaptor<ModeChangeCallback> mModeChangeCallbackCaptor;
    @Captor private ArgumentCaptor<PrimaryClientModeManagerChangedCallback> mPrimaryChangedCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mPrimaryManager.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mSecondaryManager.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);

        mBroadcastQueue = new ClientModeManagerBroadcastQueue(mActiveModeWarden);

        verify(mActiveModeWarden).registerModeChangeCallback(mModeChangeCallbackCaptor.capture());
        verify(mActiveModeWarden)
                .registerPrimaryClientModeManagerChangedCallback(mPrimaryChangedCaptor.capture());
    }

    @Test
    public void primaryManagerSendBroadcast_sentImmediately() {
        mBroadcastQueue.queueOrSendBroadcast(mPrimaryManager, mQueuedBroadcast1);

        verify(mQueuedBroadcast1).send();
    }

    @Test
    public void secondaryManagerSendBroadcast_queuedAndSentOnPrimaryChangeInOrderAndCleared() {
        InOrder order = inOrder(mQueuedBroadcast1, mQueuedBroadcast2, mQueuedBroadcast3);

        // send first broadcast - not sent
        mBroadcastQueue.queueOrSendBroadcast(mSecondaryManager, mQueuedBroadcast1);
        order.verify(mQueuedBroadcast1, never()).send();

        // send second broadcast - neither first nor second sent
        mBroadcastQueue.queueOrSendBroadcast(mSecondaryManager, mQueuedBroadcast2);
        order.verify(mQueuedBroadcast1, never()).send();
        order.verify(mQueuedBroadcast2, never()).send();

        // primary changed - queued broadcasts sent in order
        when(mSecondaryManager.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mPrimaryManager.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        mPrimaryChangedCaptor.getValue().onChange(mPrimaryManager, mSecondaryManager);
        order.verify(mQueuedBroadcast1).send();
        order.verify(mQueuedBroadcast2).send();

        // send third broadcast - triggered immediately, first and second not triggered again
        mBroadcastQueue.queueOrSendBroadcast(mSecondaryManager, mQueuedBroadcast3);
        order.verify(mQueuedBroadcast1, never()).send();
        order.verify(mQueuedBroadcast2, never()).send();
        order.verify(mQueuedBroadcast3).send();

        // primary changed back
        when(mPrimaryManager.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mSecondaryManager.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        mPrimaryChangedCaptor.getValue().onChange(mSecondaryManager, mPrimaryManager);

        // primary changed again
        when(mSecondaryManager.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mPrimaryManager.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        mPrimaryChangedCaptor.getValue().onChange(mPrimaryManager, mSecondaryManager);

        // broadcasts not triggered again (verify that the queued broadcasts were cleared)
        order.verify(mQueuedBroadcast1, never()).send();
        order.verify(mQueuedBroadcast2, never()).send();
        order.verify(mQueuedBroadcast3, never()).send();
    }

    @Test
    public void secondaryManagerSendBroadcast_clientModeManagerRemoved_queueCleared() {
        InOrder order = inOrder(mQueuedBroadcast1, mQueuedBroadcast2, mQueuedBroadcast3);

        // queue broadcasts
        mBroadcastQueue.queueOrSendBroadcast(mSecondaryManager, mQueuedBroadcast1);
        mBroadcastQueue.queueOrSendBroadcast(mSecondaryManager, mQueuedBroadcast2);

        // remove manager
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRemoved(mSecondaryManager);

        // broadcasts not sent
        order.verify(mQueuedBroadcast1, never()).send();
        order.verify(mQueuedBroadcast2, never()).send();

        // trigger queued broadcasts
        when(mSecondaryManager.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mPrimaryManager.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        mPrimaryChangedCaptor.getValue().onChange(mPrimaryManager, mSecondaryManager);

        // broadcasts still not sent because queue should have been cleared
        order.verify(mQueuedBroadcast1, never()).send();
        order.verify(mQueuedBroadcast2, never()).send();
    }
}
