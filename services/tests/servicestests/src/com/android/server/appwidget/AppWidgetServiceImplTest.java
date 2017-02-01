/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.appwidget;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManagerInternal;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.appwidget.PendingHostUpdate;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutServiceInternal;
import android.os.Handler;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.server.LocalServices;

import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;


/**
 * Tests for {@link AppWidgetManager} and {@link AppWidgetServiceImpl}.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.appwidget.AppWidgetServiceImplTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class AppWidgetServiceImplTest extends InstrumentationTestCase {

    private static final int HOST_ID = 42;

    private TestContext mTestContext;
    private String mPkgName;
    private AppWidgetServiceImpl mService;
    private AppWidgetManager mManager;

    private ShortcutServiceInternal mMockShortcutService;
    private IAppWidgetHost mMockHost;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        mTestContext = new TestContext();
        mPkgName = mTestContext.getOpPackageName();
        mService = new AppWidgetServiceImpl(mTestContext);
        mManager = new AppWidgetManager(mTestContext, mService);

        mMockShortcutService = mock(ShortcutServiceInternal.class);
        mMockHost = mock(IAppWidgetHost.class);
        LocalServices.addService(ShortcutServiceInternal.class, mMockShortcutService);
        mService.onStart();
    }

    public void testRequestPinAppWidget_otherProvider() {
        ComponentName otherProvider = null;
        for (AppWidgetProviderInfo provider : mManager.getInstalledProviders()) {
            if (!provider.provider.getPackageName().equals(mTestContext.getPackageName())) {
                otherProvider = provider.provider;
                break;
            }
        }
        if (otherProvider == null) {
            // No other provider found. Ignore this test.
        }
        assertFalse(mManager.requestPinAppWidget(otherProvider, null));
    }

    public void testRequestPinAppWidget() {
        ComponentName provider = new ComponentName(mTestContext, DummyAppWidget.class);
        // Set up users.
        when(mMockShortcutService.requestPinAppWidget(anyString(),
                any(AppWidgetProviderInfo.class), any(IntentSender.class), anyInt()))
                .thenReturn(true);
        assertTrue(mManager.requestPinAppWidget(provider, null));

        final ArgumentCaptor<AppWidgetProviderInfo> providerCaptor =
                ArgumentCaptor.forClass(AppWidgetProviderInfo.class);
        verify(mMockShortcutService, times(1)).requestPinAppWidget(anyString(),
                providerCaptor.capture(), eq(null), anyInt());
        assertEquals(provider, providerCaptor.getValue().provider);
    }

    public void testIsRequestPinAppWidgetSupported() {
        ComponentName provider = new ComponentName(mTestContext, DummyAppWidget.class);
        // Set up users.
        when(mMockShortcutService.isRequestPinItemSupported(anyInt(), anyInt()))
                .thenReturn(true, false);
        assertTrue(mManager.isRequestPinAppWidgetSupported());
        assertFalse(mManager.isRequestPinAppWidgetSupported());

        verify(mMockShortcutService, times(2)).isRequestPinItemSupported(anyInt(),
                eq(LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET));
    }

    public void testProviderUpdatesReceived() throws Exception {
        int widgetId = setupHostAndWidget();
        RemoteViews view = new RemoteViews(mPkgName, android.R.layout.simple_list_item_1);
        mManager.updateAppWidget(widgetId, view);
        mManager.updateAppWidget(widgetId, view);
        mManager.updateAppWidget(widgetId, view);
        mManager.updateAppWidget(widgetId, view);

        flushMainThread();
        verify(mMockHost, times(4)).updateAppWidget(eq(widgetId), any(RemoteViews.class));

        reset(mMockHost);
        mManager.notifyAppWidgetViewDataChanged(widgetId, 22);
        flushMainThread();
        verify(mMockHost, times(1)).viewDataChanged(eq(widgetId), eq(22));
    }

    public void testProviderUpdatesNotReceived() throws Exception {
        int widgetId = setupHostAndWidget();
        mService.stopListening(mPkgName, HOST_ID);
        RemoteViews view = new RemoteViews(mPkgName, android.R.layout.simple_list_item_1);
        mManager.updateAppWidget(widgetId, view);
        mManager.notifyAppWidgetViewDataChanged(widgetId, 22);

        flushMainThread();
        verify(mMockHost, times(0)).updateAppWidget(anyInt(), any(RemoteViews.class));
        verify(mMockHost, times(0)).viewDataChanged(anyInt(), eq(22));
    }

    public void testNoUpdatesReceived_queueEmpty() {
        int widgetId = setupHostAndWidget();
        RemoteViews view = new RemoteViews(mPkgName, android.R.layout.simple_list_item_1);
        mManager.updateAppWidget(widgetId, view);
        mManager.notifyAppWidgetViewDataChanged(widgetId, 22);
        mService.stopListening(mPkgName, HOST_ID);

        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[0]).getList();
        assertTrue(updates.isEmpty());
    }

    /**
     * Sends dummy widget updates to {@link #mManager}.
     * @param widgetId widget to update
     * @param viewIds a list of view ids for which
     *                {@link AppWidgetManager#notifyAppWidgetViewDataChanged} will be called
     */
    private void sendDummyUpdates(int widgetId, int... viewIds) {
        Random r = new Random();
        RemoteViews view = new RemoteViews(mPkgName, android.R.layout.simple_list_item_1);
        for (int i = r.nextInt(10) + 2; i >= 0; i--) {
            mManager.updateAppWidget(widgetId, view);
        }

        for (int viewId : viewIds) {
            mManager.notifyAppWidgetViewDataChanged(widgetId, viewId);
            for (int i = r.nextInt(3); i >= 0; i--) {
                mManager.updateAppWidget(widgetId, view);
            }
        }
    }

    public void testNoUpdatesReceived_queueNonEmpty_noWidgetId() {
        int widgetId = setupHostAndWidget();
        mService.stopListening(mPkgName, HOST_ID);

        sendDummyUpdates(widgetId, 22, 23);
        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[0]).getList();
        assertTrue(updates.isEmpty());
    }

    public void testUpdatesReceived_queueNotEmpty_widgetIdProvided() {
        int widgetId = setupHostAndWidget();
        int widgetId2 = bindNewWidget();
        mService.stopListening(mPkgName, HOST_ID);

        sendDummyUpdates(widgetId, 22, 23);
        sendDummyUpdates(widgetId2, 100, 101, 102);

        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[]{widgetId}).getList();
        // 3 updates corresponding to the first widget
        assertEquals(3, updates.size());
    }

    public void testUpdatesReceived_queueNotEmpty_widgetIdProvided2() {
        int widgetId = setupHostAndWidget();
        int widgetId2 = bindNewWidget();
        mService.stopListening(mPkgName, HOST_ID);

        sendDummyUpdates(widgetId, 22, 23);
        sendDummyUpdates(widgetId2, 100, 101, 102);

        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[]{widgetId2}).getList();
        // 4 updates corresponding to the second widget
        assertEquals(4, updates.size());
    }

    public void testUpdatesReceived_queueNotEmpty_multipleWidgetIdProvided() {
        int widgetId = setupHostAndWidget();
        int widgetId2 = bindNewWidget();
        mService.stopListening(mPkgName, HOST_ID);

        sendDummyUpdates(widgetId, 22, 23);
        sendDummyUpdates(widgetId2, 100, 101, 102);

        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[]{widgetId, widgetId2}).getList();
        // 3 updates for first widget and 4 for second
        assertEquals(7, updates.size());
    }

    private int setupHostAndWidget() {
        List<PendingHostUpdate> updates = mService.startListening(
                mMockHost, mPkgName, HOST_ID, new int[0]).getList();
        assertTrue(updates.isEmpty());
        return bindNewWidget();
    }

    private int bindNewWidget() {
        ComponentName provider = new ComponentName(mTestContext, DummyAppWidget.class);
        int widgetId = mService.allocateAppWidgetId(mPkgName, HOST_ID);
        assertTrue(mManager.bindAppWidgetIdIfAllowed(widgetId, provider));
        assertEquals(provider, mManager.getAppWidgetInfo(widgetId).provider);

        return widgetId;
    }

    private void flushMainThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(mTestContext.getMainLooper()).post(latch::countDown);
        latch.await();
    }

    private class TestContext extends ContextWrapper {

        public TestContext() {
            super(getInstrumentation().getContext());
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            // ignore.
            return null;
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            // ignore.
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            // ignore.
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            // ignore.
        }
    }
}
