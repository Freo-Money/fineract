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
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.AbstractLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.LoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;

/**
 * This {@link LoanRepaymentScheduleTransactionProcessor} processes payments in the order:
 * <ul>
 * <li>For ALL installments (in installment order): Interest, then Principal</li>
 * <li>After P+I is fully paid across ALL installments: for each installment: Penalties, then Fees</li>
 * </ul>
 *
 * Notes:
 * <ul>
 * <li>Penalties/fees are applied regardless of installment dueDate; only principal/interest outstanding controls
 * whether charges are paid.</li>
 * </ul>
 */
public class OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor
        extends AbstractLoanRepaymentScheduleTransactionProcessor {

    public static final String STRATEGY_CODE = "overdue-due-adv-interest-principal-overdue-due-penalties-fees-order-strategy";

    public static final String STRATEGY_NAME = "Overdue/Due Adv Interest/Principal, Overdue/Due Penalties/Fees Order";

    public OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
            final ExternalIdFactory externalIdFactory, final LoanChargeValidator loanChargeValidator,
            final LoanBalanceService loanBalanceService) {
        super(externalIdFactory, loanChargeValidator, loanBalanceService);
    }

    @Override
    public String getCode() {
        return STRATEGY_CODE;
    }

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean isInterestFirstRepaymentScheduleTransactionProcessor() {
        return true;
    }

    /**
     * For early/'in advance' repayments, delegate to on-time handling.
     */
    @SuppressWarnings("unused")
    @Override
    protected Money handleTransactionThatIsPaymentInAdvanceOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final List<LoanRepaymentScheduleInstallment> installments, final LoanTransaction loanTransaction, final Money paymentInAdvance,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, final Set<LoanCharge> charges) {
        return handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment, loanTransaction, paymentInAdvance, transactionMappings,
                charges);
    }

    @Override
    protected Money handleTransactionThatIsALateRepaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final List<LoanRepaymentScheduleInstallment> installments, final LoanTransaction loanTransaction,
            final Money transactionAmountUnprocessed, final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings,
            final Set<LoanCharge> charges) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();
        Money transactionAmountRemaining = transactionAmountUnprocessed;
        Money interestWaivedPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        if (loanTransaction.isChargesWaiver()) {
            penaltyChargesPortion = currentInstallment.waivePenaltyChargesComponent(transactionDate,
                    loanTransaction.getPenaltyChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);

            feeChargesPortion = currentInstallment.waiveFeeChargesComponent(transactionDate,
                    loanTransaction.getFeeChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);

            final Money principalPortion = Money.zero(currency);
            final Money interestPortion = Money.zero(currency);
            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
            if (penaltyChargesPortion.plus(feeChargesPortion).isGreaterThanZero()) {
                transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                        principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
            }
        } else if (loanTransaction.isInterestWaiver()) {
            interestWaivedPortion = currentInstallment.waiveInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestWaivedPortion);

            final Money principalPortion = Money.zero(currency);
            loanTransaction.updateComponents(principalPortion, interestWaivedPortion, feeChargesPortion, penaltyChargesPortion);
            if (interestWaivedPortion.isGreaterThanZero()) {
                transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                        principalPortion, interestWaivedPortion, feeChargesPortion, penaltyChargesPortion));
            }
        } else if (loanTransaction.isChargePayment()) {
            final Money principalPortion = Money.zero(currency);
            final Money interestPortion = Money.zero(currency);
            if (loanTransaction.isPenaltyPayment()) {
                penaltyChargesPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
            } else {
                feeChargesPortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
            }
            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
            if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
                transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                        principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
            }
        } else {
            return handleTransactionThatIsNormalRepaymentAcrossAllInstallmentsThenOverduePenaltiesFees(installments, loanTransaction,
                    transactionAmountUnprocessed, transactionMappings);
        }

        return transactionAmountRemaining;
    }

    @Override
    protected Money handleTransactionThatIsOnTimePaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final LoanTransaction loanTransaction, final Money transactionAmountUnprocessed,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, final Set<LoanCharge> charges) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();

        Money transactionAmountRemaining = transactionAmountUnprocessed;
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        if (loanTransaction.isChargesWaiver()) {
            penaltyChargesPortion = currentInstallment.waivePenaltyChargesComponent(transactionDate,
                    loanTransaction.getPenaltyChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);

            feeChargesPortion = currentInstallment.waiveFeeChargesComponent(transactionDate,
                    loanTransaction.getFeeChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
        } else if (loanTransaction.isInterestWaiver()) {
            interestPortion = currentInstallment.waiveInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);
        } else if (loanTransaction.isChargePayment()) {
            if (loanTransaction.isPenaltyPayment()) {
                penaltyChargesPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
            } else {
                feeChargesPortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
            }
        } else {
            // Normal repayment:
            final List<LoanRepaymentScheduleInstallment> allInstallments = currentInstallment.getLoan().getRepaymentScheduleInstallments();
            return handleTransactionThatIsNormalRepaymentAcrossAllInstallmentsThenOverduePenaltiesFees(allInstallments, loanTransaction,
                    transactionAmountUnprocessed, transactionMappings);
        }

        loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }

    private Money handleTransactionThatIsNormalRepaymentAcrossAllInstallmentsThenOverduePenaltiesFees(
            final List<LoanRepaymentScheduleInstallment> installments, final LoanTransaction loanTransaction,
            final Money transactionAmountUnprocessed, final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();
        Money transactionAmountRemaining = transactionAmountUnprocessed;

        // First pass: pay Interest then Principal for ALL installments.
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (!transactionAmountRemaining.isGreaterThanZero()) {
                break;
            }

            Money principalPortion = Money.zero(currency);
            Money interestPortion = Money.zero(currency);

            if (transactionAmountRemaining.isGreaterThanZero() && installment.isInterestDue(currency)) {
                interestPortion = installment.payInterestComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);
            }

            if (installment.isPrincipalNotCompleted(currency)) {
                principalPortion = installment.payPrincipalComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(principalPortion);
            }

            if (principalPortion.plus(interestPortion).isGreaterThanZero()) {
                loanTransaction.updateComponents(principalPortion, interestPortion, Money.zero(currency), Money.zero(currency));
                transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment, principalPortion,
                        interestPortion, Money.zero(currency), Money.zero(currency)));
            }
        }

        // Only if there is no principal/interest outstanding across ALL installments, pay penalties/fees.
        boolean hasPrincipalOrInterestOutstanding = false;
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (installment.getPrincipalOutstanding(currency).isGreaterThanZero()
                    || installment.getInterestOutstanding(currency).isGreaterThanZero()) {
                hasPrincipalOrInterestOutstanding = true;
                break;
            }
        }
        if (hasPrincipalOrInterestOutstanding) {
            return transactionAmountRemaining;
        }

        // Second pass: pay Penalties then Fees for ALL installments.
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (!transactionAmountRemaining.isGreaterThanZero()) {
                break;
            }

            final boolean hasPenaltyOrFeeOutstanding = installment.getPenaltyChargesOutstanding(currency).isGreaterThanZero()
                    || installment.getFeeChargesOutstanding(currency).isGreaterThanZero();
            if (!hasPenaltyOrFeeOutstanding) {
                continue;
            }

            Money feeChargesPortionForInstallment = Money.zero(currency);
            Money penaltyChargesPortionForInstallment = Money.zero(currency);

            if (installment.getPenaltyChargesOutstanding(currency).isGreaterThanZero() && transactionAmountRemaining.isGreaterThanZero()) {
                penaltyChargesPortionForInstallment = installment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortionForInstallment);
            }

            if (installment.getFeeChargesOutstanding(currency).isGreaterThanZero() && transactionAmountRemaining.isGreaterThanZero()) {
                feeChargesPortionForInstallment = installment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortionForInstallment);
            }

            if (penaltyChargesPortionForInstallment.plus(feeChargesPortionForInstallment).isGreaterThanZero()) {
                loanTransaction.updateComponents(Money.zero(currency), Money.zero(currency), feeChargesPortionForInstallment,
                        penaltyChargesPortionForInstallment);

                // Check if mapping already exists for this installment due date.
                boolean isMappingUpdated = false;
                for (LoanTransactionToRepaymentScheduleMapping repaymentScheduleMapping : transactionMappings) {
                    if (repaymentScheduleMapping.getLoanRepaymentScheduleInstallment().getDueDate().equals(installment.getDueDate())) {
                        repaymentScheduleMapping.updateComponents(Money.zero(currency), Money.zero(currency),
                                feeChargesPortionForInstallment, penaltyChargesPortionForInstallment);
                        isMappingUpdated = true;
                        break;
                    }
                }
                if (!isMappingUpdated) {
                    transactionMappings
                            .add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment, Money.zero(currency),
                                    Money.zero(currency), feeChargesPortionForInstallment, penaltyChargesPortionForInstallment));
                }
            }
        }

        return transactionAmountRemaining;
    }

    @Override
    protected Money handleRefundTransactionPaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final LoanTransaction loanTransaction, final Money transactionAmountUnprocessed,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money transactionAmountRemaining = transactionAmountUnprocessed;

        final MonetaryCurrency currency = transactionAmountRemaining.getCurrency();
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        // Reverse order: Fees, Penalties, Principal, Interest
        if (transactionAmountRemaining.isGreaterThanZero()) {
            feeChargesPortion = currentInstallment.unpayFeeChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            penaltyChargesPortion = currentInstallment.unpayPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            principalPortion = currentInstallment.unpayPrincipalComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(principalPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            interestPortion = currentInstallment.unpayInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);
        }

        loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }
}
