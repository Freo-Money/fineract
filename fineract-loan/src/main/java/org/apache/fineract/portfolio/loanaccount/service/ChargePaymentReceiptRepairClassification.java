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
 * Outcome of comparing a charge payment's stored receipts with its amount and allocation.
 */
public enum ChargePaymentReceiptRepairClassification {

    /** Receipts sum to the amount and the fee/penalty portions absorb all of it. */
    CONSISTENT(false),
    /** Receipts sum below the amount and topping clipped receipts up to their charge amounts closes the gap exactly. */
    CLIPPED(true),
    /** Receipts sum below the amount; topping up closes part of the gap only. Not repaired, needs manual review. */
    CLIPPED_RESIDUAL(false),
    /** Receipts sum below the amount and every receipt already equals its charge amount. Nothing to top up. */
    SURPLUS(false),
    /** Receipts sum above the amount. */
    OVER(false),
    /** Receipts sum to the amount but the installment buckets could not absorb all of it (installment-level drift). */
    INSTALLMENT_ROOM(false),
    /** Receipts span both fee and penalty charges. Not expected; refused. */
    MIXED(false),
    /** At least one receipt points to an inactive or missing charge. */
    RECEIPT_ON_INACTIVE_CHARGE(false),
    /** At least one receipt maps to no installment by number or due period (would use the latest-due fallback). */
    UNMAPPED_RECEIPT(false),
    /** Legacy single-receipt charge payment: replay derives from the charge, receipts are not its input. */
    SINGLE_RECEIPT(false);

    private final boolean repairable;

    ChargePaymentReceiptRepairClassification(final boolean repairable) {
        this.repairable = repairable;
    }

    public boolean isRepairable() {
        return repairable;
    }
}
