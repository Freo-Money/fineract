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
import org.apache.fineract.infrastructure.core.service.DateUtils;
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
 * This {@link LoanRepaymentScheduleTransactionProcessor} processes payments in the order: For each overdue/due
 * installment: Principal, then Interest (e.g. overdue Principal inst1, overdue Interest inst1, overdue Principal inst2,
 * overdue Interest inst2, ...). Then for each overdue/due installment: Fees, then Penalties.
 *
 * Example: With 3 overdue installments and 1 due installment: Payment order: Principal1, Interest1, Principal2,
 * Interest2, Principal3, Interest3, Principal4, Interest4, then Fees1, Penalties1, Fees2, Penalties2, Fees3,
 * Penalties3, Fees4, Penalties4.
 */
public class OverdueDuePrincipalInterestOverdueDueFeesPenaltiesOrderLoanRepaymentScheduleTransactionProcessor
        extends AbstractLoanRepaymentScheduleTransactionProcessor {

    public static final String STRATEGY_CODE = "overdue-due-principal-interest-overdue-due-fees-penalties-order-strategy";

    public static final String STRATEGY_NAME = "Overdue/Principal/Interest,Fees";

    public OverdueDuePrincipalInterestOverdueDueFeesPenaltiesOrderLoanRepaymentScheduleTransactionProcessor(
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

    /**
     * For early/'in advance' repayments, uses the same logic as on-time payments.
     */
    @SuppressWarnings("unused")
    @Override
    protected Money handleTransactionThatIsPaymentInAdvanceOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final List<LoanRepaymentScheduleInstallment> installments, final LoanTransaction loanTransaction, final Money paymentInAdvance,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges) {

        return handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment, loanTransaction, paymentInAdvance, transactionMappings,
                charges);
    }

    /**
     * For late repayments, pays Principal+Interest for each overdue/due installment in order, then Fees+Penalties for
     * each overdue/due installment in order.
     */
    @Override
    protected Money handleTransactionThatIsALateRepaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final List<LoanRepaymentScheduleInstallment> installments, final LoanTransaction loanTransaction,
            final Money transactionAmountUnprocessed, List<LoanTransactionToRepaymentScheduleMapping> transactionMappings,
            Set<LoanCharge> charges) {

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

            final Money principalPortion = Money.zero(transactionAmountRemaining.getCurrency());
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
            final LoanRepaymentScheduleInstallment currentInstallmentBasedOnTransactionDate = nearestInstallment(
                    loanTransaction.getTransactionDate(), installments);

            // First pass: Pay Principal+Interest for each overdue/due installment
            for (final LoanRepaymentScheduleInstallment installment : installments) {
                if ((installment.isPrincipalNotCompleted(currency) || installment.isInterestDue(currency))
                        && (installment.isOverdueOn(loanTransaction.getTransactionDate()) || installment.getInstallmentNumber()
                                .equals(currentInstallmentBasedOnTransactionDate.getInstallmentNumber()))
                        && transactionAmountRemaining.isGreaterThanZero()) {

                    Money principalPortion = Money.zero(currency);
                    Money interestPortion = Money.zero(currency);
                    Money feeChargesPortionForInstallment = Money.zero(currency);
                    Money penaltyChargesPortionForInstallment = Money.zero(currency);

                    // Pay Principal first, then Interest
                    if (installment.isPrincipalNotCompleted(currency) && transactionAmountRemaining.isGreaterThanZero()) {
                        principalPortion = installment.payPrincipalComponent(transactionDate, transactionAmountRemaining);
                        transactionAmountRemaining = transactionAmountRemaining.minus(principalPortion);
                    }

                    if (installment.isInterestDue(currency) && transactionAmountRemaining.isGreaterThanZero()) {
                        interestPortion = installment.payInterestComponent(transactionDate, transactionAmountRemaining);
                        transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);
                    }

                    loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortionForInstallment,
                            penaltyChargesPortionForInstallment);
                    if (principalPortion.plus(interestPortion).isGreaterThanZero()) {
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment,
                                principalPortion, interestPortion, feeChargesPortionForInstallment, penaltyChargesPortionForInstallment));
                    }
                }
            }

            // Second pass: Pay Fees+Penalties for each overdue/due installment
            for (final LoanRepaymentScheduleInstallment installment : installments) {
                if ((installment.getPenaltyChargesOutstanding(currency).isGreaterThanZero()
                        || installment.getFeeChargesOutstanding(currency).isGreaterThanZero())
                        && (installment.isOverdueOn(loanTransaction.getTransactionDate()) || installment.getInstallmentNumber()
                                .equals(currentInstallmentBasedOnTransactionDate.getInstallmentNumber()))
                        && transactionAmountRemaining.isGreaterThanZero()) {

                    Money principalPortion = Money.zero(currency);
                    Money interestPortion = Money.zero(currency);
                    Money feeChargesPortionForInstallment = Money.zero(currency);
                    Money penaltyChargesPortionForInstallment = Money.zero(currency);

                    // Pay Fees first, then Penalties
                    if (installment.getFeeChargesOutstanding(currency).isGreaterThanZero()
                            && transactionAmountRemaining.isGreaterThanZero()) {
                        feeChargesPortionForInstallment = installment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                        transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortionForInstallment);
                    }

                    if (installment.getPenaltyChargesOutstanding(currency).isGreaterThanZero()
                            && transactionAmountRemaining.isGreaterThanZero()) {
                        penaltyChargesPortionForInstallment = installment.payPenaltyChargesComponent(transactionDate,
                                transactionAmountRemaining);
                        transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortionForInstallment);
                    }

                    loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortionForInstallment,
                            penaltyChargesPortionForInstallment);
                    if (penaltyChargesPortionForInstallment.plus(feeChargesPortionForInstallment).isGreaterThanZero()) {
                        // Check if mapping already exists for this installment
                        boolean isMappingUpdated = false;
                        for (LoanTransactionToRepaymentScheduleMapping repaymentScheduleMapping : transactionMappings) {
                            if (repaymentScheduleMapping.getLoanRepaymentScheduleInstallment().getDueDate()
                                    .equals(installment.getDueDate())) {
                                repaymentScheduleMapping.updateComponents(principalPortion, interestPortion,
                                        feeChargesPortionForInstallment, penaltyChargesPortionForInstallment);
                                isMappingUpdated = true;
                                break;
                            }
                        }
                        if (!isMappingUpdated) {
                            transactionMappings.add(
                                    LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment, principalPortion,
                                            interestPortion, feeChargesPortionForInstallment, penaltyChargesPortionForInstallment));
                        }
                    }
                }
            }
        }

        return transactionAmountRemaining;
    }

    private LoanRepaymentScheduleInstallment nearestInstallment(final LocalDate transactionDate,
            final List<LoanRepaymentScheduleInstallment> installments) {
        LoanRepaymentScheduleInstallment nearest = installments.get(0); // installments must be sorted by dates
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (DateUtils.isBefore(transactionDate, installment.getDueDate())) {
                break;
            }
            nearest = installment;
        }
        return nearest;
    }

    /**
     * For normal on-time repayments, pays Principal first, then Interest, then Fees, then Penalties.
     */
    @Override
    protected Money handleTransactionThatIsOnTimePaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final LoanTransaction loanTransaction, final Money transactionAmountUnprocessed,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();
        Money transactionAmountRemaining = transactionAmountUnprocessed;
        Money principalPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money interestPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money feeChargesPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money penaltyChargesPortion = Money.zero(transactionAmountRemaining.getCurrency());

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
            // Pay Principal first, then Interest
            principalPortion = currentInstallment.payPrincipalComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(principalPortion);

            interestPortion = currentInstallment.payInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);

            // Then pay Fees, then Penalties
            feeChargesPortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);

            penaltyChargesPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
        }

        loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }

    @Override
    public boolean isInterestFirstRepaymentScheduleTransactionProcessor() {
        return false;
    }

    @Override
    protected Money handleRefundTransactionPaymentOfInstallment(final LoanRepaymentScheduleInstallment currentInstallment,
            final LoanTransaction loanTransaction, final Money transactionAmountUnprocessed,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money transactionAmountRemaining = transactionAmountUnprocessed;
        Money principalPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money interestPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money feeChargesPortion = Money.zero(transactionAmountRemaining.getCurrency());
        Money penaltyChargesPortion = Money.zero(transactionAmountRemaining.getCurrency());

        // Reverse order: Penalties, Fees, Interest, Principal
        if (transactionAmountRemaining.isGreaterThanZero()) {
            penaltyChargesPortion = currentInstallment.unpayPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            feeChargesPortion = currentInstallment.unpayFeeChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            interestPortion = currentInstallment.unpayInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);
        }

        if (transactionAmountRemaining.isGreaterThanZero()) {
            principalPortion = currentInstallment.unpayPrincipalComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(principalPortion);
        }

        loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }
}
