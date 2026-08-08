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
package org.apache.fineract.portfolio.loanaccount.reprocess;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parameters for {@code POST /loans/{loanId}?command=reprocessLoan}.
 * <p>
 * The command takes any loan parameter whose change invalidates the repayment schedule, rewrites it, regenerates the
 * schedule from inception and replays the existing transactions against the result.
 * <p>
 * Distinct from {@code command=reprocessTransactions}, which re-allocates transactions against the schedule <i>as it
 * stands</i> and never regenerates it. Distinct also from loan rescheduling, which is forward-looking and leaves
 * inception untouched.
 * <p>
 * {@link #CORRECTABLE_PARAMETERS} is the full intended surface; {@link #IMPLEMENTED_PARAMETERS} is what is actually
 * wired up today. Anything in the first set but not the second is rejected up front rather than silently ignored, so
 * adding a parameter later is a matter of implementing its apply step and moving its name across.
 */
public final class LoanReprocessApiConstants {

    private LoanReprocessApiConstants() {}

    public static final String COMMAND_REPROCESS_LOAN = "reprocessLoan";

    public static final String localeParamName = "locale";
    public static final String dateFormatParamName = "dateFormat";
    public static final String noteParamName = "note";

    /**
     * Response-only key for the {@code changes} map: the first repayment date the date move pinned as a side effect.
     * Deliberately not {@link #expectedFirstRepaymentOnDateParamName} - that parameter is still rejected as
     * unimplemented when supplied, and the audit record must not read as though it had been accepted.
     */
    public static final String expectedFirstRepaymentOnDatePinnedResponseParam = "expectedFirstRepaymentOnDatePinned";

    // --- correctable parameters ---
    public static final String actualDisbursementDateParamName = "actualDisbursementDate";
    public static final String principalParamName = "principal";
    public static final String expectedFirstRepaymentOnDateParamName = "expectedFirstRepaymentOnDate";
    public static final String interestRatePerPeriodParamName = "interestRatePerPeriod";
    public static final String numberOfRepaymentsParamName = "numberOfRepayments";

    /** Everything this command is intended to support, in the order it is applied. */
    public static final Set<String> CORRECTABLE_PARAMETERS = new LinkedHashSet<>(Set.of(actualDisbursementDateParamName, principalParamName,
            expectedFirstRepaymentOnDateParamName, interestRatePerPeriodParamName, numberOfRepaymentsParamName));

    /**
     * What is wired up today. Everything else is a declared placeholder: accepted by the JSON schema so the contract is
     * visible, but rejected by the validator until its apply step exists.
     */
    public static final Set<String> IMPLEMENTED_PARAMETERS = Set.of(actualDisbursementDateParamName);

    public static final Set<String> SUPPORTED_PARAMETERS = Set.of(localeParamName, dateFormatParamName, noteParamName,
            actualDisbursementDateParamName, principalParamName, expectedFirstRepaymentOnDateParamName, interestRatePerPeriodParamName,
            numberOfRepaymentsParamName);

    // --- error codes, full literals so they are greppable ---
    public static final String ERROR_NO_PARAMETER_SUPPLIED = "error.msg.loan.reprocess.no.parameter.supplied";
    public static final String ERROR_PARAMETER_NOT_IMPLEMENTED = "error.msg.loan.reprocess.parameter.not.yet.supported";
    public static final String ERROR_NOT_ACTIVE = "error.msg.loan.reprocess.loan.is.not.active";
    public static final String ERROR_TOPUP = "error.msg.loan.reprocess.not.allowed.on.topup.loan";
    public static final String ERROR_MIGRATED_LOAN = "error.msg.loan.reprocess.not.allowed.on.migrated.loan";
    public static final String ERROR_MULTIPLE_DISBURSEMENT_DETAILS = "error.msg.loan.reprocess.multiple.disbursement.details";
    public static final String ERROR_NO_LIVE_DISBURSEMENT = "error.msg.loan.reprocess.no.live.disbursement.transaction";
    public static final String ERROR_COB_LOCKED = "error.msg.loan.reprocess.loan.locked.by.cob";
    public static final String ERROR_LINKED_TRANSACTIONS = "error.msg.loan.reprocess.not.allowed.with.linked.transactions";
    public static final String ERROR_ACCOUNT_TRANSFER_LINKED = "error.msg.loan.reprocess.not.allowed.with.account.transfer";
    public static final String ERROR_DOWN_PAYMENT_PRESENT = "error.msg.loan.reprocess.not.allowed.with.down.payment";
    public static final String ERROR_REPAYMENT_ON_DISBURSEMENT_DATE = "error.msg.loan.reprocess.not.allowed.with.repayment.on.disbursement.date";
    public static final String ERROR_ACCOUNTING_CLOSED = "error.msg.loan.reprocess.accounting.closed";
    public static final String ERROR_MULTI_DISBURSE_UNSUPPORTED = "error.msg.loan.reprocess.multi.disbursement.not.supported";
    public static final String ERROR_PROGRESSIVE_UNSUPPORTED = "error.msg.loan.reprocess.progressive.schedule.not.supported";
    public static final String ERROR_INTEREST_RECALCULATION_UNSUPPORTED = "error.msg.loan.reprocess.interest.recalculation.not.supported";
    public static final String ERROR_DISBURSEMENT_DATE_UNCHANGED = "error.msg.loan.reprocess.disbursement.date.unchanged";
    public static final String ERROR_DISBURSEMENT_DATE_IN_FUTURE = "error.msg.loan.reprocess.disbursement.date.in.future";
    public static final String ERROR_DISBURSEMENT_BEFORE_APPROVAL = "error.msg.loan.reprocess.disbursement.date.before.approval.date";
    public static final String ERROR_DISBURSEMENT_AFTER_TRANSACTION = "error.msg.loan.reprocess.disbursement.date.after.existing.transaction";
    public static final String ERROR_DISBURSEMENT_NOT_BEFORE_FIRST_INSTALLMENT = "error.msg.loan.reprocess.disbursement.date.not.before.first.installment";
}
