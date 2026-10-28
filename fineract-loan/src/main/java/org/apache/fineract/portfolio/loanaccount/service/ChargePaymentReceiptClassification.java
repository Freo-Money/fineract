/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.service;

/**
 * How a charge payment's stored receipts compare with its amount. Only {@link #CLIPPED} is topped up.
 */
public enum ChargePaymentReceiptClassification {

    /** Receipts sum to the amount and the fee/penalty portions absorb all of it. */
    CONSISTENT,
    /**
     * Receipts sum below the amount, the room left on their charges covers the gap and no other short charge payment
     * has receipts on any of those charges that still has room. Receipts are raised in effective-due-date order until
     * the gap is closed.
     */
    CLIPPED,
    /**
     * Receipts sum below the amount and the charges' room closes only part of the gap. Left for refund. Decided before
     * {@link #AMBIGUOUS_ROOM}, so another short charge payment may also have receipts on a charge with that room: check
     * the loan for one before taking gap minus room as the refund.
     */
    CLIPPED_RESIDUAL,
    /** Receipts sum below the amount and no charge has room left. */
    SURPLUS,
    /**
     * Receipts sum below the amount, the room left on their charges covers the gap, and another short charge payment
     * has receipts on charges with room left, so that room could belong to either payment. Left for manual review. Used
     * where the payment would otherwise be {@link #CLIPPED} or {@link #INSTALLMENT_ROOM}.
     */
    AMBIGUOUS_ROOM,
    /** Receipts sum above the amount. */
    OVER,
    /**
     * The installment buckets did not absorb all of the receipts (stored fee/penalty below the receipts total), so
     * raising receipts cannot reconcile the payment. Used where the payment would otherwise be {@link #CONSISTENT} or
     * {@link #CLIPPED}.
     */
    INSTALLMENT_ROOM,
    /** Receipts span both fee and penalty charges. */
    MIXED,
    /** At least one receipt points to an inactive or missing charge. */
    RECEIPT_ON_INACTIVE_CHARGE,
    /**
     * At least one receipt pays an instalment fee or a charge due at disbursement. Their replay is not bounded by the
     * charge's room (one installment's share, or no installment at all), so they are not topped up.
     */
    UNSUPPORTED_CHARGE,
    /** Single receipt on an active charge: replay re-derives it from the charge, so it is never clipped. */
    SINGLE_RECEIPT,
    /** Charge payment without receipts: replay has no charge to apply it to and parks the whole amount. */
    NO_RECEIPTS
}
