/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothCallback;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.bluetooth.R;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AdapterServiceTest {
    private AdapterService mAdapterService;

    private @Mock Context mMockContext;
    private @Mock ApplicationInfo mMockApplicationInfo;
    private @Mock AlarmManager mMockAlarmManager;
    private @Mock Resources mMockResources;
    private @Mock UserManager mMockUserManager;
    private @Mock ProfileService mMockGattService;
    private @Mock ProfileService mMockService;
    private @Mock ProfileService mMockService2;
    private @Mock IBluetoothCallback mIBluetoothCallback;
    private @Mock Binder mBinder;

    private static final int CONTEXT_SWITCH_MS = 100;
    private static final int ONE_SECOND_MS = 1000;
    private static final int NATIVE_INIT_MS = 8000;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Assert.assertNotNull(Looper.myLooper());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mAdapterService = new AdapterService();
            }
        });
        PackageManager mMockPackageManager = mock(PackageManager.class);
        MockContentResolver mMockContentResolver = new MockContentResolver(mMockContext);
        MockitoAnnotations.initMocks(this);
        PowerManager powerManager = (PowerManager) InstrumentationRegistry.getTargetContext()
                .getSystemService(Context.POWER_SERVICE);

        when(mMockContext.getApplicationInfo()).thenReturn(mMockApplicationInfo);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getUserId()).thenReturn(Process.BLUETOOTH_UID);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(mMockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);
        when(mMockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mMockAlarmManager);

        when(mMockResources.getBoolean(R.bool.profile_supported_gatt)).thenReturn(true);
        when(mMockResources.getBoolean(R.bool.profile_supported_pbap)).thenReturn(true);
        when(mMockResources.getBoolean(R.bool.profile_supported_pan)).thenReturn(true);

        when(mIBluetoothCallback.asBinder()).thenReturn(mBinder);

        doReturn(Process.BLUETOOTH_UID).when(mMockPackageManager)
                .getPackageUidAsUser(any(), anyInt(), anyInt());

        when(mMockGattService.getName()).thenReturn("GattService");
        when(mMockService.getName()).thenReturn("Service1");
        when(mMockService2.getName()).thenReturn("Service2");

        // Attach a context to the service for permission checks.
        mAdapterService.attach(mMockContext, null, null, null, null, null);

        mAdapterService.onCreate();
        mAdapterService.registerCallback(mIBluetoothCallback);

        Config.init(mMockContext);
    }

    @After
    public void tearDown() {
        mAdapterService.unregisterCallback(mIBluetoothCallback);
        mAdapterService.cleanup();
        Config.init(InstrumentationRegistry.getTargetContext());
    }

    private void verifyStateChange(int prevState, int currState, int callNumber, int timeoutMs) {
        try {
            verify(mIBluetoothCallback, timeout(timeoutMs)
                    .times(callNumber)).onBluetoothStateChange(prevState, currState);
        } catch (Exception e) {
            // the mocked onBluetoothStateChange doesn't throw exceptions
        }
    }

    private void doEnable(int invocationNumber) {
        Assert.assertFalse(mAdapterService.isEnabled());

        mAdapterService.enable();

        verifyStateChange(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_BLE_TURNING_ON,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        // Start GATT
        verify(mMockContext,
                timeout(CONTEXT_SWITCH_MS).times(6 * invocationNumber + 1)).startService(any());
        mAdapterService.addProfile(mMockGattService);
        mAdapterService.onProfileServiceStateChanged(mMockGattService, BluetoothAdapter.STATE_ON);

        verifyStateChange(BluetoothAdapter.STATE_BLE_TURNING_ON, BluetoothAdapter.STATE_BLE_ON,
                invocationNumber + 1, NATIVE_INIT_MS);

        mAdapterService.onLeServiceUp();

        verifyStateChange(BluetoothAdapter.STATE_BLE_ON, BluetoothAdapter.STATE_TURNING_ON,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        // Start Mock PBAP and PAN services
        verify(mMockContext, timeout(ONE_SECOND_MS).times(6 * invocationNumber + 3)).startService(
                any());
        mAdapterService.addProfile(mMockService);
        mAdapterService.addProfile(mMockService2);
        mAdapterService.onProfileServiceStateChanged(mMockService, BluetoothAdapter.STATE_ON);
        mAdapterService.onProfileServiceStateChanged(mMockService2, BluetoothAdapter.STATE_ON);

        verifyStateChange(BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_ON,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        Assert.assertTrue(mAdapterService.isEnabled());
    }

    private void doDisable(int invocationNumber) {
        Assert.assertTrue(mAdapterService.isEnabled());

        mAdapterService.disable();

        verifyStateChange(BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_TURNING_OFF,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        // Stop PBAP and PAN
        verify(mMockContext, timeout(ONE_SECOND_MS).times(6 * invocationNumber + 5)).startService(
                any());
        mAdapterService.onProfileServiceStateChanged(mMockService, BluetoothAdapter.STATE_OFF);
        mAdapterService.onProfileServiceStateChanged(mMockService2, BluetoothAdapter.STATE_OFF);

        verifyStateChange(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_BLE_ON,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        mAdapterService.onBrEdrDown();

        verifyStateChange(BluetoothAdapter.STATE_BLE_ON, BluetoothAdapter.STATE_BLE_TURNING_OFF,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        // Stop GATT
        verify(mMockContext, timeout(ONE_SECOND_MS).times(6 * invocationNumber + 6)).startService(
                any());
        mAdapterService.onProfileServiceStateChanged(mMockGattService, BluetoothAdapter.STATE_OFF);

        verifyStateChange(BluetoothAdapter.STATE_BLE_TURNING_OFF, BluetoothAdapter.STATE_OFF,
                invocationNumber + 1, CONTEXT_SWITCH_MS);

        Assert.assertFalse(mAdapterService.isEnabled());
    }

    /**
     * Test: Turn Bluetooth on.
     * Check whether the AdapterService gets started.
     */
    @Test
    public void testEnable() {
        doEnable(0);
    }

    /**
     * Test: Turn Bluetooth on/off.
     * Check whether the AdapterService gets started and stopped.
     */
    @Test
    public void testEnableDisable() {
        doEnable(0);
        doDisable(0);
    }
}
