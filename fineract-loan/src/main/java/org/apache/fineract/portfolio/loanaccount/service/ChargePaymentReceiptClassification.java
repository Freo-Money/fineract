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
    /** Receipts sum below the amount and raising them within their charges' room closes the gap exactly. */
    CLIPPED,
    /** Receipts sum below the amount and the charges' room closes only part of the gap. Left for refund. */
    CLIPPED_RESIDUAL,
    /** Receipts sum below the amount and no charge has room left. */
    SURPLUS,
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
