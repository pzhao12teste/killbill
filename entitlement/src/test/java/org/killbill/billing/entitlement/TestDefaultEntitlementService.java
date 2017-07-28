/*
 * Copyright 2014-2017 Groupon, Inc
 * Copyright 2014-2017 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.entitlement;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.PriceListSet;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementSourceType;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementState;
import org.killbill.billing.entitlement.api.EntitlementApiException;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKey;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKeyAction;
import org.killbill.billing.entitlement.plugin.api.EntitlementContext;
import org.killbill.billing.entitlement.plugin.api.EntitlementPluginApi;
import org.killbill.billing.entitlement.plugin.api.EntitlementPluginApiException;
import org.killbill.billing.entitlement.plugin.api.OnFailureEntitlementResult;
import org.killbill.billing.entitlement.plugin.api.OnSuccessEntitlementResult;
import org.killbill.billing.entitlement.plugin.api.PriorEntitlementResult;
import org.killbill.billing.osgi.api.OSGIServiceDescriptor;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.util.listener.RetryableService;
import org.killbill.notificationq.api.NotificationEventWithMetadata;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;

public class TestDefaultEntitlementService extends EntitlementTestSuiteWithEmbeddedDB {

    private TestEntitlementPluginApi testEntitlementPluginApi;

    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        super.beforeClass();

        this.testEntitlementPluginApi = new TestEntitlementPluginApi();
        pluginRegistry.registerService(new OSGIServiceDescriptor() {
            @Override
            public String getPluginSymbolicName() {
                return "TestEntitlementPluginApi";
            }

            @Override
            public String getPluginName() {
                return "TestEntitlementPluginApi";
            }

            @Override
            public String getRegistrationName() {
                return "TestEntitlementPluginApi";
            }
        }, testEntitlementPluginApi);
    }

    @Test(groups = "slow")
    public void testWithRetries() throws AccountApiException, EntitlementApiException, NoSuchNotificationQueue, IOException {
        final LocalDate initialDate = new LocalDate(2013, 8, 7);
        clock.setDay(initialDate);
        final Account account = createAccount(getAccountData(7));

        // Create entitlement
        testListener.pushExpectedEvents(NextEvent.CREATE, NextEvent.BLOCK);
        final PlanPhaseSpecifier spec = new PlanPhaseSpecifier("Shotgun", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, null);
        final Entitlement entitlement = entitlementApi.createBaseEntitlement(account.getId(), spec, account.getExternalKey(), null, null, null, false, ImmutableList.<PluginProperty>of(), callContext);
        assertListenerStatus();
        assertEquals(entitlement.getState(), EntitlementState.ACTIVE);
        assertEquals(entitlement.getSourceType(), EntitlementSourceType.NATIVE);

        assertEquals(getFutureEntitlementNotifications().size(), 0);

        // Make entitlement plugin throw an exception
        testEntitlementPluginApi.shouldThrowException = true;

        // Trigger entitlement plugin from notification queue
        // TODO It doesn't look like this is a codepath triggered today?
        final NotificationQueue subscriptionEventQueue = notificationQueueService.getNotificationQueue(DefaultEntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                                                       DefaultEntitlementService.NOTIFICATION_QUEUE_NAME);
        final DateTime originalEffectiveDate = clock.getUTCNow();
        subscriptionEventQueue.recordFutureNotification(originalEffectiveDate,
                                                        new EntitlementNotificationKey(entitlement.getBaseEntitlementId(), entitlement.getBundleId(), EntitlementNotificationKeyAction.PAUSE, clock.getUTCNow()),
                                                        internalCallContext.getUserToken(),
                                                        internalCallContext.getAccountRecordId(),
                                                        internalCallContext.getTenantRecordId());
        // No change
        assertListenerStatus();

        // Verify notification has moved to the retry service
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return getFutureEntitlementRetryableNotifications().size() == 1;
            }
        });

        assertEquals(entitlementApi.getEntitlementForId(entitlement.getId(), callContext).getState(), EntitlementState.ACTIVE);

        // Verify 5' retry
        List<NotificationEventWithMetadata> futureEntitlementRetryableNotifications = getFutureEntitlementRetryableNotifications();
        assertEquals(futureEntitlementRetryableNotifications.size(), 1);
        assertEquals(futureEntitlementRetryableNotifications.get(0).getEffectiveDate().compareTo(originalEffectiveDate.plusMinutes(5)), 0);
        // Main queue unaffected
        assertEquals(getFutureEntitlementNotifications().size(), 0);

        assertEquals(entitlementApi.getEntitlementForId(entitlement.getId(), callContext).getState(), EntitlementState.ACTIVE);

        // Add 5'
        clock.addDeltaFromReality(5 * 60 * 1000);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                final List<NotificationEventWithMetadata> notifications = getFutureEntitlementRetryableNotifications();
                return notifications.size() == 1 && notifications.get(0).getEffectiveDate().compareTo(originalEffectiveDate.plusMinutes(15)) == 0;
            }
        });

        // Verify 15' retry
        futureEntitlementRetryableNotifications = getFutureEntitlementRetryableNotifications();
        assertEquals(futureEntitlementRetryableNotifications.size(), 1);
        assertEquals(futureEntitlementRetryableNotifications.get(0).getEffectiveDate().compareTo(originalEffectiveDate.plusMinutes(15)), 0);
        // Main queue unaffected
        assertEquals(getFutureEntitlementNotifications().size(), 0);

        assertEquals(entitlementApi.getEntitlementForId(entitlement.getId(), callContext).getState(), EntitlementState.ACTIVE);

        // Make entitlement plugin not an exception
        testEntitlementPluginApi.shouldThrowException = false;

        // Add 10'
        testListener.pushExpectedEvents(NextEvent.BLOCK);
        clock.addDeltaFromReality(10 * 60 * 1000);
        assertListenerStatus();

        assertEquals(getFutureEntitlementRetryableNotifications().size(), 0);
        assertEquals(getFutureEntitlementNotifications().size(), 0);
        assertEquals(entitlementApi.getEntitlementForId(entitlement.getId(), callContext).getState(), EntitlementState.BLOCKED);
    }

    private List<NotificationEventWithMetadata> getFutureEntitlementNotifications() throws NoSuchNotificationQueue {
        final NotificationQueue notificationQueue = notificationQueueService.getNotificationQueue(EntitlementService.ENTITLEMENT_SERVICE_NAME, DefaultEntitlementService.NOTIFICATION_QUEUE_NAME);
        return ImmutableList.<NotificationEventWithMetadata>copyOf(notificationQueue.getFutureNotificationForSearchKeys(internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId()));
    }

    private List<NotificationEventWithMetadata> getFutureEntitlementRetryableNotifications() throws NoSuchNotificationQueue {
        final NotificationQueue notificationQueue = notificationQueueService.getNotificationQueue(RetryableService.RETRYABLE_SERVICE_NAME, DefaultEntitlementService.NOTIFICATION_QUEUE_NAME);
        return ImmutableList.<NotificationEventWithMetadata>copyOf(notificationQueue.getFutureNotificationForSearchKeys(internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId()));
    }

    private static final class TestEntitlementPluginApi implements EntitlementPluginApi {

        boolean shouldThrowException = false;

        @Override
        public PriorEntitlementResult priorCall(final EntitlementContext context, final Iterable<PluginProperty> properties) throws EntitlementPluginApiException {
            if (shouldThrowException) {
                throw new IllegalStateException("EXPECTED");
            }
            return null;
        }

        @Override
        public OnSuccessEntitlementResult onSuccessCall(final EntitlementContext context, final Iterable<PluginProperty> properties) throws EntitlementPluginApiException {
            return null;
        }

        @Override
        public OnFailureEntitlementResult onFailureCall(final EntitlementContext context, final Iterable<PluginProperty> properties) throws EntitlementPluginApiException {
            return null;
        }
    }
}
