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

package org.killbill.billing.invoice.notification;

import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.invoice.InvoiceListener;
import org.killbill.billing.invoice.api.DefaultInvoiceService;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.listener.RetryException;
import org.killbill.billing.util.listener.RetryableHandler;
import org.killbill.billing.util.listener.RetryableService;
import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationEvent;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService.NotificationQueueAlreadyExists;
import org.killbill.notificationq.api.NotificationQueueService.NotificationQueueHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class ParentInvoiceCommitmentNotifier extends RetryableService implements NextBillingDateNotifier {

    public static final String PARENT_INVOICE_COMMITMENT_NOTIFIER_QUEUE = "parent-invoice-commitment-queue";

    private static final Logger log = LoggerFactory.getLogger(ParentInvoiceCommitmentNotifier.class);

    private final Clock clock;
    private final NotificationQueueService notificationQueueService;
    private final InvoiceListener listener;
    private final InternalCallContextFactory internalCallContextFactory;

    private NotificationQueue commitInvoiceQueue;

    @Inject
    public ParentInvoiceCommitmentNotifier(final Clock clock,
                                           final NotificationQueueService notificationQueueService,
                                           final InvoiceListener listener,
                                           final InternalCallContextFactory internalCallContextFactory) {
        super(notificationQueueService, internalCallContextFactory);
        this.clock = clock;
        this.notificationQueueService = notificationQueueService;
        this.listener = listener;
        this.internalCallContextFactory = internalCallContextFactory;
    }

    @Override
    public void initialize() throws NotificationQueueAlreadyExists {
        final NotificationQueueHandler notificationQueueHandler = new NotificationQueueHandler() {
            @Override
            public void handleReadyNotification(final NotificationEvent notificationKey, final DateTime eventDate, final UUID userToken, final Long accountRecordId, final Long tenantRecordId) {
                try {
                    if (!(notificationKey instanceof ParentInvoiceCommitmentNotificationKey)) {
                        log.error("Invoice service received an unexpected event type {}", notificationKey.getClass().getName());
                        throw new RetryException();
                    }

                    final ParentInvoiceCommitmentNotificationKey key = (ParentInvoiceCommitmentNotificationKey) notificationKey;

                    listener.handleParentInvoiceCommitmentEvent(key.getUuidKey(), userToken, accountRecordId, tenantRecordId);
                } catch (final IllegalArgumentException e) {
                    throw new RetryException(e);
                } catch (final InvoiceApiException e) {
                    throw new RetryException(e);
                }
            }
        };

        final NotificationQueueHandler retryableHandler = new RetryableHandler(clock, this, notificationQueueHandler, internalCallContextFactory);
        commitInvoiceQueue = notificationQueueService.createNotificationQueue(DefaultInvoiceService.INVOICE_SERVICE_NAME,
                                                                              PARENT_INVOICE_COMMITMENT_NOTIFIER_QUEUE,
                                                                              retryableHandler);

        super.initialize(commitInvoiceQueue, notificationQueueHandler);
    }

    @Override
    public void start() {
        super.start();

        commitInvoiceQueue.startQueue();
    }

    @Override
    public void stop() throws NoSuchNotificationQueue {
        if (commitInvoiceQueue != null) {
            commitInvoiceQueue.stopQueue();
            notificationQueueService.deleteNotificationQueue(commitInvoiceQueue.getServiceName(), commitInvoiceQueue.getQueueName());
        }

        super.stop();
    }
}
