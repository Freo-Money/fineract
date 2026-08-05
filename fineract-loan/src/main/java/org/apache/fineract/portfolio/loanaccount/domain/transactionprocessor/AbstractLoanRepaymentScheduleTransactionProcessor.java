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
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor;

import static java.math.BigDecimal.ZERO;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrualAdjustment;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrueTransaction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargePaidDetail;
import org.apache.fineract.portfolio.loanaccount.data.TransactionChangeData;
import org.apache.fineract.portfolio.loanaccount.domain.ChangedTransactionDetail;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeEffectiveDueDateComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeOffBehaviour;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelation;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.domain.SingleLoanChargeRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.CreocoreLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.HeavensFamilyLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.InterestPrincipalPenaltyFeesOrderLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

/**
 * Abstract implementation of {@link LoanRepaymentScheduleTransactionProcessor} which is more convenient for concrete
 * implementations to extend.
 *
 * @see InterestPrincipalPenaltyFeesOrderLoanRepaymentScheduleTransactionProcessor
 *
 * @see HeavensFamilyLoanRepaymentScheduleTransactionProcessor
 * @see CreocoreLoanRepaymentScheduleTransactionProcessor
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractLoanRepaymentScheduleTransactionProcessor implements LoanRepaymentScheduleTransactionProcessor {

    protected final SingleLoanChargeRepaymentScheduleProcessingWrapper loanChargeProcessor = new SingleLoanChargeRepaymentScheduleProcessingWrapper();
    protected final ExternalIdFactory externalIdFactory;
    protected final LoanChargeValidator loanChargeValidator;
    protected final LoanBalanceService loanBalanceService;
    private LoanProductRoundingModeService loanProductRoundingModeService;

    @Autowired(required = false)
    public void setLoanProductRoundingModeService(final LoanProductRoundingModeService loanProductRoundingModeService) {
        this.loanProductRoundingModeService = loanProductRoundingModeService;
    }

    protected MathContext resolveMathContext(final Loan loan) {
        if (loanProductRoundingModeService != null && loan != null && loan.getLoanProduct() != null) {
            return loanProductRoundingModeService.resolveMathContext(loan.getLoanProduct().getId());
        }
        return MoneyHelper.getMathContext();
    }

    @Override
    public boolean accept(String s) {
        return getCode().equalsIgnoreCase(s) || getName().equalsIgnoreCase(s);
    }

    @Override
    public ChangedTransactionDetail reprocessLoanTransactions(final LocalDate disbursementDate,
            final List<LoanTransaction> transactionsPostDisbursement, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges) {

        if (!transactionsPostDisbursement.isEmpty()) {
            final Loan loan = transactionsPostDisbursement.get(0).getLoan();
            loan.subtractFromTotalExcessPaymentAmount(
                    Money.of(loan.getCurrency(), Optional.ofNullable(loan.getTotalExcessPaymentAmount()).orElse(BigDecimal.ZERO)));
        }

        if (charges != null) {
            for (final LoanCharge loanCharge : charges) {
                if (!loanCharge.isDueAtDisbursement()) {
                    loanCharge.resetPaidAmount(currency);
                }
            }
        }
        addChargeOnlyRepaymentInstallmentIfRequired(charges, installments);

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            currentInstallment.resetDerivedComponents();
        }

        // re-process loan charges over repayment periods (picking up on waived
        // loan charges)
        final LoanRepaymentScheduleProcessingWrapper wrapper = new LoanRepaymentScheduleProcessingWrapper();
        wrapper.reprocess(currency, disbursementDate, installments, charges);

        // Must run after the wrapper fills charge portions, else the zero-amount post-maturity bucket is marked fully
        // paid, and since processTransaction skips fully-paid installments, charge payments targeting it fall through
        // to overpayment.
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            currentInstallment.updateObligationsMet(currency, disbursementDate);
        }

        final ChangedTransactionDetail changedTransactionDetail = new ChangedTransactionDetail();
        final List<LoanTransaction> transactionsToBeProcessed = new ArrayList<>();
        for (final LoanTransaction loanTransaction : transactionsPostDisbursement) {
            if (loanTransaction.isChargePayment()) {
                List<LoanChargePaidDetail> chargePaidDetails = new ArrayList<>();
                final Set<LoanChargePaidBy> chargePaidBies = loanTransaction.getLoanChargesPaid();
                final Set<LoanCharge> transferCharges = new HashSet<>();
                for (final LoanChargePaidBy chargePaidBy : chargePaidBies) {
                    transferCharges.add(chargePaidBy.getLoanCharge());
                }
                int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber(installments);
                if (hasMultipleChargeAllocations(chargePaidBies)) {
                    // Multi-charge payment: the stored receipts are authoritative - they were built as exact
                    // (charge, installment, amount) allocations that sum to the transaction amount. Replay them as-is.
                    // Re-deriving the amounts from each charge's CURRENT full amount and due period (the legacy path
                    // below) is wrong here: a partially-allocated charge would be processed for its full amount and
                    // starve the remaining allocations, and any later change to a charge's amount would shift money
                    // between allocations on every reprocess, so the applied portions would no longer match the
                    // receipts or the transaction amount.
                    for (final LoanChargePaidBy chargePaidBy : chargePaidBies) {
                        final LoanRepaymentScheduleInstallment installment = findInstallmentForChargePaidBy(installments,
                                firstNormalInstallmentNumber, chargePaidBy);
                        if (installment != null) {
                            chargePaidDetails.add(new LoanChargePaidDetail(Money.of(currency, chargePaidBy.getAmount()), installment,
                                    chargePaidBy.getLoanCharge().isFeeCharge()));
                        } else {
                            // Only reachable when the loan has no installments at all; surface it rather than silently
                            // diverting the allocation to overpayment.
                            final LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                            log.warn("Charge payment reprocess: could not map LoanChargePaidBy [id={}] (loanChargeId={}, amount={}) "
                                    + "on transaction [id={}] of loan [id={}] to any installment; its amount will fall through to overpayment.",
                                    chargePaidBy.getId(), loanCharge != null ? loanCharge.getId() : null, chargePaidBy.getAmount(),
                                    loanTransaction.getId(), loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null);
                        }
                    }
                } else {
                    for (final LoanCharge loanCharge : transferCharges) {
                        if (loanCharge.isInstalmentFee()) {
                            chargePaidDetails.addAll(loanCharge.fetchRepaymentInstallment(currency));
                        }
                    }
                    LocalDate startDate = disbursementDate;
                    for (final LoanRepaymentScheduleInstallment installment : installments) {
                        boolean isFirstPeriod = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
                        for (final LoanCharge loanCharge : transferCharges) {
                            boolean isDue = loanCharge.isDueInPeriod(startDate, installment.getDueDate(), isFirstPeriod);
                            if (isDue) {
                                Money amountForProcess = loanCharge.getAmount(currency);
                                if (amountForProcess.isGreaterThan(loanTransaction.getAmount(currency))) {
                                    amountForProcess = loanTransaction.getAmount(currency);
                                }
                                LoanChargePaidDetail chargePaidDetail = new LoanChargePaidDetail(amountForProcess, installment,
                                        loanCharge.isFeeCharge());
                                chargePaidDetails.add(chargePaidDetail);
                            }
                        }
                        startDate = installment.getDueDate();
                    }
                }
                loanTransaction.resetDerivedComponents();
                // A charge payment is processed one charge at a time below, each in its own processTransaction call.
                // Clear the existing installment mappings once up front so that the per-charge calls can accumulate
                // (rather than overwrite) portions when multiple charges fall in the same installment.
                loanTransaction.clearLoanTransactionToRepaymentScheduleMappings();
                Money unprocessed = loanTransaction.getAmount(currency);
                for (LoanChargePaidDetail chargePaidDetail : chargePaidDetails) {
                    final List<LoanRepaymentScheduleInstallment> processInstallments = new ArrayList<>(1);
                    processInstallments.add(chargePaidDetail.getInstallment());
                    Money processAmt = chargePaidDetail.getAmount();
                    if (processAmt.isGreaterThan(unprocessed)) {
                        processAmt = unprocessed;
                    }
                    Money chargeLeftover = handleTransactionAndCharges(loanTransaction, currency, processInstallments, transferCharges,
                            processAmt, chargePaidDetail.isFeeCharge());
                    unprocessed = unprocessed.minus(processAmt).plus(chargeLeftover);
                    if (!unprocessed.isGreaterThanZero()) {
                        break;
                    }
                }

                if (unprocessed.isGreaterThanZero()) {
                    onLoanOverpayment(loanTransaction, unprocessed);
                    loanTransaction.setOverPayments(unprocessed);
                }

            } else {
                transactionsToBeProcessed.add(loanTransaction);
            }
        }

        MoneyHolder overpaymentHolder = new MoneyHolder(Money.zero(currency));
        for (final LoanTransaction loanTransaction : transactionsToBeProcessed) {
            // TODO: analyze and remove this
            if (!loanTransaction.getTypeOf().equals(LoanTransactionType.REFUND_FOR_ACTIVE_LOAN)) {
                final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator
                        .comparing(LoanRepaymentScheduleInstallment::getDueDate);
                installments.sort(byDate);
            }

            if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
                // pass through for new transactions
                if (loanTransaction.getId() == null) {
                    processLatestTransaction(loanTransaction, new TransactionCtx(currency, installments, charges, overpaymentHolder, null));
                    loanTransaction.adjustInterestComponent();
                } else {
                    /**
                     * For existing transactions, check if the re-payment breakup (principal, interest, fees, penalties)
                     * has changed.<br>
                     **/
                    final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

                    // Reset derived component of new loan transaction and
                    // re-process transaction
                    processLatestTransaction(newLoanTransaction,
                            new TransactionCtx(currency, installments, charges, overpaymentHolder, null));
                    newLoanTransaction.adjustInterestComponent();
                    /**
                     * Check if the transaction amounts have changed. If so, reverse the original transaction and update
                     * changedTransactionDetail accordingly
                     **/
                    if (newLoanTransaction.isReversed()) {
                        loanTransaction.reverse();
                        changedTransactionDetail.addTransactionChange(new TransactionChangeData(loanTransaction, loanTransaction));
                    } else if (LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
                        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(
                                newLoanTransaction.getLoanTransactionToRepaymentScheduleMappings());
                    } else {
                        createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
                    }
                }

            } else if (loanTransaction.isWriteOff()) {
                loanTransaction.resetDerivedComponents();
                handleWriteOff(loanTransaction, currency, installments);
            } else if (loanTransaction.isRefundForActiveLoan()) {
                loanTransaction.resetDerivedComponents();
                handleRefund(loanTransaction, currency, installments, charges);
            } else if (loanTransaction.isCreditBalanceRefund()) {
                recalculateCreditTransaction(changedTransactionDetail, loanTransaction, currency, installments, overpaymentHolder);
            } else if (loanTransaction.isChargeback()) {
                recalculateCreditTransaction(changedTransactionDetail, loanTransaction, currency, installments, overpaymentHolder);
                reprocessChargebackTransactionRelation(changedTransactionDetail, transactionsToBeProcessed);
            } else if (loanTransaction.isChargeOff()) {
                recalculateChargeOffTransaction(changedTransactionDetail, loanTransaction, currency, installments);
            }
        }
        reprocessInstallments(disbursementDate, transactionsToBeProcessed, installments, currency);
        return changedTransactionDetail;
    }

    @Override
    public ChangedTransactionDetail processLatestTransaction(final LoanTransaction loanTransaction, final TransactionCtx ctx) {
        switch (loanTransaction.getTypeOf()) {
            case WRITEOFF -> handleWriteOff(loanTransaction, ctx.getCurrency(), ctx.getInstallments());
            case REFUND_FOR_ACTIVE_LOAN -> handleRefund(loanTransaction, ctx.getCurrency(), ctx.getInstallments(), ctx.getCharges());
            case CHARGEBACK -> handleChargeback(loanTransaction, ctx);
            case CHARGE_OFF -> handleChargeOff(loanTransaction, ctx);
            default -> {
                if (loanTransaction.isChargePayment()) {
                    // Charge payments accumulate (rather than overwrite) their installment mappings in
                    // processTransaction, since one payment is processed one charge at a time. That accumulation is
                    // only correct starting from an empty set, so reset here to guarantee a re-processed or
                    // re-submitted charge payment can never double its mappings, regardless of caller.
                    loanTransaction.clearLoanTransactionToRepaymentScheduleMappings();
                }
                Money transactionAmountUnprocessed = handleTransactionAndCharges(loanTransaction, ctx.getCurrency(), ctx.getInstallments(),
                        ctx.getCharges(), null, false);
                if (transactionAmountUnprocessed.isGreaterThanZero()) {
                    if (loanTransaction.isWaiver()) {
                        loanTransaction.updateComponentsAndTotal(transactionAmountUnprocessed.zero(), transactionAmountUnprocessed.zero(),
                                transactionAmountUnprocessed.zero(), transactionAmountUnprocessed.zero());
                    } else {
                        onLoanOverpayment(loanTransaction, transactionAmountUnprocessed);
                        loanTransaction.setOverPayments(transactionAmountUnprocessed);
                    }
                    ctx.getOverpaymentHolder()
                            .setMoneyObject(ctx.getOverpaymentHolder().getMoneyObject().add(transactionAmountUnprocessed));
                } else {
                    ctx.getOverpaymentHolder().setMoneyObject(Money.zero(ctx.getCurrency()));
                }
            }
        }
        return ctx.getChangedTransactionDetail();
    }

    @Override
    public Money handleRepaymentSchedule(final List<LoanTransaction> transactionsPostDisbursement, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, Set<LoanCharge> loanCharges) {
        Money unProcessed = Money.zero(currency);
        for (final LoanTransaction loanTransaction : transactionsPostDisbursement) {
            if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
                loanTransaction.resetDerivedComponents();
            }
            if (loanTransaction.isInterestWaiver()) {
                processTransaction(loanTransaction, currency, installments, loanCharges, null);
            } else {
                unProcessed = processTransaction(loanTransaction, currency, installments, loanCharges, null);
            }
        }
        return unProcessed;
    }

    @Override
    public boolean isInterestFirstRepaymentScheduleTransactionProcessor() {
        return false;
    }

    // abstract interface

    /**
     * For early/'in advance' repayments.
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsPaymentInAdvanceOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money paymentInAdvance,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * For normal on-time repayments.
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsOnTimePaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * For late repayments, how should components of installment be paid off
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsALateRepaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * Invoked when a transaction results in an over-payment of the full loan.
     *
     * transaction amount is greater than the total expected principal and interest of the loan.
     */
    @SuppressWarnings("unused")
    protected void onLoanOverpayment(final LoanTransaction loanTransaction, final Money loanOverPaymentAmount) {
        // empty implementation by default.
    }

    /**
     * Invoked when a there is a refund of an active loan or undo of an active loan
     *
     * Undoes principal, interest, fees and charges of this transaction based on the repayment strategy
     *
     * @param transactionMappings
     *            TODO
     *
     */
    protected abstract Money handleRefundTransactionPaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings);

    /**
     * This method is responsible for checking if the current transaction is 'an advance/early payment' based on the
     * details passed through.
     *
     * Default implementation is check transaction date is before installment due date.
     */
    protected boolean isTransactionInAdvanceOfInstallment(final int installmentIndex,
            final List<LoanRepaymentScheduleInstallment> installments, final LocalDate transactionDate) {
        final LoanRepaymentScheduleInstallment currentInstallment = installments.get(installmentIndex);
        return DateUtils.isBefore(transactionDate, currentInstallment.getDueDate());
    }

    /**
     * This method is responsible for checking if the current transaction is 'an advance/early payment' based on the
     * details passed through.
     *
     * Default implementation simply processes transactions as 'Late' if the transaction date is after the installment
     * due date.
     */
    protected boolean isTransactionALateRepaymentOnInstallment(final int installmentIndex,
            final List<LoanRepaymentScheduleInstallment> installments, final LocalDate transactionDate) {
        final LoanRepaymentScheduleInstallment currentInstallment = installments.get(installmentIndex);
        return DateUtils.isAfter(transactionDate, currentInstallment.getDueDate());
    }

    private void recalculateChargeOffTransaction(ChangedTransactionDetail changedTransactionDetail, LoanTransaction loanTransaction,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> installments) {
        final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

        final BigDecimal newInterest = getInterestTillChargeOffForPeriod(newLoanTransaction.getLoan(),
                newLoanTransaction.getTransactionDate());
        createMissingAccrualTransactionDuringChargeOffIfNeeded(newInterest, newLoanTransaction, newLoanTransaction.getTransactionDate(),
                changedTransactionDetail);

        newLoanTransaction.resetDerivedComponents();
        // determine how much is outstanding total and breakdown for principal, interest and charges
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltychargesPortion = Money.zero(currency);
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            if (currentInstallment.isNotFullyPaidOff()) {
                principalPortion = principalPortion.plus(currentInstallment.getPrincipalOutstanding(currency));
                interestPortion = interestPortion.plus(currentInstallment.getInterestOutstanding(currency));
                feeChargesPortion = feeChargesPortion.plus(currentInstallment.getFeeChargesOutstanding(currency));
                penaltychargesPortion = penaltychargesPortion.plus(currentInstallment.getPenaltyChargesOutstanding(currency));
            }
        }

        newLoanTransaction.updateComponentsAndTotal(principalPortion, interestPortion, feeChargesPortion, penaltychargesPortion);
        if (!LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
            createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
        }
    }

    private void reprocessChargebackTransactionRelation(ChangedTransactionDetail changedTransactionDetail,
            List<LoanTransaction> transactionsToBeProcessed) {
        List<LoanTransaction> mergedTransactionList = getMergedTransactionList(transactionsToBeProcessed, changedTransactionDetail);
        for (TransactionChangeData change : changedTransactionDetail.getTransactionChanges()) {
            LoanTransaction newTransaction = change.getNewTransaction();
            LoanTransaction oldTransaction = change.getOldTransaction();

            if (newTransaction.isChargeback()) {
                for (LoanTransaction loanTransaction : mergedTransactionList) {
                    if (loanTransaction.isReversed()) {
                        continue;
                    }
                    LoanTransactionRelation newLoanTransactionRelation = null;
                    LoanTransactionRelation oldLoanTransactionRelation = null;
                    for (LoanTransactionRelation transactionRelation : loanTransaction.getLoanTransactionRelations()) {
                        if (LoanTransactionRelationTypeEnum.CHARGEBACK.equals(transactionRelation.getRelationType())
                                && oldTransaction != null && oldTransaction.getId() != null
                                && oldTransaction.getId().equals(transactionRelation.getToTransaction().getId())) {
                            newLoanTransactionRelation = LoanTransactionRelation.linkToTransaction(loanTransaction, newTransaction,
                                    LoanTransactionRelationTypeEnum.CHARGEBACK);
                            oldLoanTransactionRelation = transactionRelation;
                            break;
                        }
                    }
                    if (newLoanTransactionRelation != null) {
                        loanTransaction.getLoanTransactionRelations().add(newLoanTransactionRelation);
                        loanTransaction.getLoanTransactionRelations().remove(oldLoanTransactionRelation);
                    }
                }
            }
        }
    }

    protected void reprocessInstallments(LocalDate disbursementDate, List<LoanTransaction> transactions,
            List<LoanRepaymentScheduleInstallment> installments, MonetaryCurrency currency) {
        LoanRepaymentScheduleInstallment lastInstallment = installments.getLast();
        if (lastInstallment.isAdditional() && lastInstallment.getDue(currency).isZero()) {
            installments.remove(lastInstallment);
        }

        if (isNotObligationsMet(lastInstallment) || isObligationsMetOnDisbursementDate(disbursementDate, lastInstallment)) {
            Optional<LoanTransaction> optWaiverTx = transactions.stream().filter(lt -> {
                LocalDate fromDate = lastInstallment.getFromDate();
                return lt.getTransactionDate().isAfter(fromDate);
            }).filter(LoanTransaction::isChargesWaiver).max(Comparator.comparing(LoanTransaction::getTransactionDate));
            if (optWaiverTx.isPresent()) {
                LoanTransaction waiverTx = optWaiverTx.get();
                LocalDate waiverTxDate = waiverTx.getTransactionDate();
                if (isNotObligationsMet(lastInstallment) || isTransactionAfterObligationsMetOnDate(waiverTxDate, lastInstallment)) {
                    lastInstallment.updateObligationMet(true);
                    lastInstallment.updateObligationMetOnDate(waiverTxDate);
                }
            }
        }

        // TODO: rewrite and handle it at the proper place when disbursement handling got fixed
        for (LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment : installments) {
            if (loanRepaymentScheduleInstallment.getTotalOutstanding(currency).isGreaterThanZero()) {
                loanRepaymentScheduleInstallment.updateObligationMet(false);
                loanRepaymentScheduleInstallment.updateObligationMetOnDate(null);
            }
        }
    }

    private boolean isTransactionAfterObligationsMetOnDate(LocalDate waiverTxDate, LoanRepaymentScheduleInstallment lastInstallment) {
        return lastInstallment.getObligationsMetOnDate() != null && lastInstallment.getObligationsMetOnDate().isBefore(waiverTxDate);
    }

    private boolean isObligationsMetOnDisbursementDate(LocalDate disbursementDate,
            LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment) {
        return loanRepaymentScheduleInstallment.isObligationsMet()
                && disbursementDate.equals(loanRepaymentScheduleInstallment.getObligationsMetOnDate());
    }

    protected boolean isNotObligationsMet(LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment) {
        return !loanRepaymentScheduleInstallment.isObligationsMet() && loanRepaymentScheduleInstallment.getObligationsMetOnDate() == null;
    }

    private void recalculateCreditTransaction(ChangedTransactionDetail changedTransactionDetail, LoanTransaction loanTransaction,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> installments, MoneyHolder overpaymentHolder) {
        // pass through for new transactions
        if (loanTransaction.getId() == null) {
            return;
        }
        final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

        processCreditTransaction(newLoanTransaction, overpaymentHolder, currency, installments);
        if (!LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
            createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
        }
    }

    private List<LoanTransaction> getMergedTransactionList(List<LoanTransaction> transactionList,
            ChangedTransactionDetail changedTransactionDetail) {
        List<LoanTransaction> mergedList = new ArrayList<>(
                changedTransactionDetail.getTransactionChanges().stream().map(TransactionChangeData::getNewTransaction).toList());
        mergedList.addAll(transactionList);
        return mergedList;
    }

    protected void createNewTransaction(LoanTransaction loanTransaction, LoanTransaction newLoanTransaction,
            ChangedTransactionDetail changedTransactionDetail) {
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(), loanTransaction, "reversed");
        loanTransaction.reverse();
        loanTransaction.updateExternalId(null);
        newLoanTransaction.copyLoanTransactionRelations(loanTransaction.getLoanTransactionRelations());
        // Adding Replayed relation from newly created transaction to reversed transaction
        newLoanTransaction.getLoanTransactionRelations().add(
                LoanTransactionRelation.linkToTransaction(newLoanTransaction, loanTransaction, LoanTransactionRelationTypeEnum.REPLAYED));
        changedTransactionDetail.addTransactionChange(new TransactionChangeData(loanTransaction, newLoanTransaction));
    }

    protected void processCreditTransaction(LoanTransaction loanTransaction, MoneyHolder overpaymentHolder, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments) {
        loanTransaction.resetDerivedComponents();
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();
        final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate);
        List<LoanRepaymentScheduleInstallment> installmentToBeProcessed = installments.stream().filter(i -> !i.isDownPayment())
                .sorted(byDate).toList();
        final Money zeroMoney = Money.zero(currency);
        Money transactionAmount = loanTransaction.getAmount(currency);
        Money principalPortion = MathUtil.negativeToZero(loanTransaction.getAmount(currency).minus(overpaymentHolder.getMoneyObject()));
        Money repaidAmount = MathUtil.negativeToZero(transactionAmount.minus(principalPortion));
        loanTransaction.setOverPayments(repaidAmount);
        overpaymentHolder.setMoneyObject(overpaymentHolder.getMoneyObject().minus(repaidAmount));
        loanTransaction.updateComponents(principalPortion, zeroMoney, zeroMoney, zeroMoney);

        if (principalPortion.isGreaterThanZero()) {
            final LocalDate transactionDate = loanTransaction.getTransactionDate();
            boolean loanTransactionMapped = false;
            LocalDate pastDueDate = null;
            for (final LoanRepaymentScheduleInstallment currentInstallment : installmentToBeProcessed) {
                pastDueDate = currentInstallment.getDueDate();
                if (!currentInstallment.isAdditional() && DateUtils.isAfter(currentInstallment.getDueDate(), transactionDate)) {
                    currentInstallment.addToCreditedPrincipal(transactionAmount.getAmount());
                    currentInstallment.addToPrincipal(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                    loanTransactionMapped = true;
                    break;

                    // If already exists an additional installment just update the due date and
                    // principal from the Loan chargeback / CBR transaction
                } else if (currentInstallment.isAdditional()) {
                    if (DateUtils.isAfter(transactionDate, currentInstallment.getDueDate())) {
                        currentInstallment.updateDueDate(transactionDate);
                    }

                    currentInstallment.updateCredits(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                    loanTransactionMapped = true;
                    break;
                }
            }

            // New installment will be added (N+1 scenario)
            if (!loanTransactionMapped) {
                if (loanTransaction.getTransactionDate().equals(pastDueDate)) {
                    LoanRepaymentScheduleInstallment currentInstallment = installmentToBeProcessed.getLast();
                    currentInstallment.addToCreditedPrincipal(transactionAmount.getAmount());
                    currentInstallment.addToPrincipal(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                } else {
                    Loan loan = loanTransaction.getLoan();
                    LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(loan, (installments.size() + 1),
                            pastDueDate, transactionDate, transactionAmount.getAmount(), zeroMoney.getAmount(), zeroMoney.getAmount(),
                            zeroMoney.getAmount(), false, null);
                    installment.markAsAdditional();
                    installment.addToCreditedPrincipal(transactionAmount.getAmount());
                    loan.addLoanRepaymentScheduleInstallment(installment);

                    if (repaidAmount.isGreaterThanZero()) {
                        installment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                }
            }

            loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
        }
    }

    protected Money handleTransactionAndCharges(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, final Money chargeAmountToProcess,
            final boolean isFeeCharge) {
        if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
            loanTransaction.resetDerivedComponents();
        }
        // Charge payments are processed one allocation at a time and the transaction's fee/penalty portions accumulate
        // across those calls (the transaction is reset once, before the per-allocation loop). Capture the portions
        // before processing this allocation so charges can be marked by the DELTA this allocation actually applied,
        // not by the running cumulative total (which would re-mark earlier allocations and push the charges'
        // amount-paid above the transaction amount).
        final Money feeChargesBefore = loanTransaction.getFeeChargesPortion(currency);
        final Money penaltyChargesBefore = loanTransaction.getPenaltyChargesPortion(currency);

        Money transactionAmountUnprocessed = processTransaction(loanTransaction, currency, installments, charges, chargeAmountToProcess);

        final Set<LoanCharge> loanFees = extractFeeCharges(charges);
        final Set<LoanCharge> loanPenalties = extractPenaltyCharges(charges);
        Integer installmentNumber = null;
        if (loanTransaction.isChargePayment() && installments.size() == 1) {
            installmentNumber = installments.getFirst().getInstallmentNumber();
        }

        if (loanTransaction.isNotWaiver() && !loanTransaction.isAccrual() && !loanTransaction.isAccrualActivity()) {
            if (loanTransaction.isChargePayment() && chargeAmountToProcess != null) {
                // Per-allocation charge payment (the reprocessing loop passes a non-null per-allocation amount). Mark
                // only the charge type this allocation targets, and only for the amount actually applied in this call
                // (the delta). This keeps the sum of the charges' amount-paid for the transaction equal to the
                // transaction amount, and avoids the cumulative double-marking that the fee-only cap below could not
                // catch for penalty allocations.
                if (isFeeCharge) {
                    final Money feeChargesDelta = loanTransaction.getFeeChargesPortion(currency).minus(feeChargesBefore);
                    if (feeChargesDelta.isGreaterThanZero()) {
                        updateChargesPaidAmountBy(loanTransaction, feeChargesDelta, loanFees, installmentNumber);
                    }
                } else {
                    final Money penaltyChargesDelta = loanTransaction.getPenaltyChargesPortion(currency).minus(penaltyChargesBefore);
                    if (penaltyChargesDelta.isGreaterThanZero()) {
                        updateChargesPaidAmountBy(loanTransaction, penaltyChargesDelta, loanPenalties, installmentNumber);
                    }
                }
            } else {
                Money feeCharges = loanTransaction.getFeeChargesPortion(currency);
                Money penaltyCharges = loanTransaction.getPenaltyChargesPortion(currency);
                if (chargeAmountToProcess != null && feeCharges.isGreaterThan(chargeAmountToProcess)) {
                    if (isFeeCharge) {
                        feeCharges = chargeAmountToProcess;
                    } else {
                        penaltyCharges = chargeAmountToProcess;
                    }
                }
                if (feeCharges.isGreaterThanZero()) {
                    updateChargesPaidAmountBy(loanTransaction, feeCharges, loanFees, installmentNumber);
                }

                if (penaltyCharges.isGreaterThanZero()) {
                    updateChargesPaidAmountBy(loanTransaction, penaltyCharges, loanPenalties, installmentNumber);
                }
            }
        }
        return transactionAmountUnprocessed;
    }

    protected Money processTransaction(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, Money amountToProcess) {
        int installmentIndex = 0;

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money transactionAmountUnprocessed = loanTransaction.getAmount(currency);
        if (amountToProcess != null) {
            transactionAmountUnprocessed = amountToProcess;
        }
        final boolean systemGenerated = isSystemGeneratedTransaction(loanTransaction);
        final Loan loan = loanTransaction.getLoan();
        // Parking is for repayments, whitelisting charge payments
        final boolean enableParking = loan.getLoanProductRelatedDetail().isEnableExcessPaymentParking() && loanTransaction.isRepayment()
                && !loanTransaction.isChargePayment();

        if (systemGenerated) {
            loanTransaction.setExcessPayment(Money.zero(currency));
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

            for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
                if (transactionAmountUnprocessed.isZero()) {
                    break;
                }
                if (currentInstallment.isNotFullyPaidOff() && !currentInstallment.getDueDate().isAfter(transactionDate)) {
                    transactionAmountUnprocessed = handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment, loanTransaction,
                            transactionAmountUnprocessed, transactionMappings, charges);
                }
            }

            loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
            if (loan.getTotalExcessPaymentAmount() != null && loan.getTotalExcessPaymentAmount().compareTo(BigDecimal.ZERO) > 0) {

                Money consumedAmount = loanTransaction.getAmount(currency);
                loan.subtractFromTotalExcessPaymentAmount(consumedAmount);

            }

            return transactionAmountUnprocessed;
        }

        if (!enableParking) {
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

            for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
                if (transactionAmountUnprocessed.isGreaterThanZero()) {
                    if (currentInstallment.isNotFullyPaidOff()) {
                        if (isTransactionInAdvanceOfInstallment(installmentIndex, installments, transactionDate)) {
                            transactionAmountUnprocessed = handleTransactionThatIsPaymentInAdvanceOfInstallment(currentInstallment,
                                    installments, loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                        } else if (isTransactionALateRepaymentOnInstallment(installmentIndex, installments, transactionDate)) {
                            transactionAmountUnprocessed = handleTransactionThatIsALateRepaymentOfInstallment(currentInstallment,
                                    installments, loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                        } else {
                            transactionAmountUnprocessed = handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment,
                                    loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                        }
                    }
                }

                installmentIndex++;
            }
            if (loanTransaction.isChargePayment()) {
                // Charge payments are processed one charge at a time; accumulate the portions into the installment
                // mapping so that multiple charges hitting the same installment are summed instead of overwritten.
                loanTransaction.addLoanTransactionToRepaymentScheduleMappings(transactionMappings);
            } else {
                loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
            }
            return transactionAmountUnprocessed;
        }

        // EXCESS PAYMENT PARKING FLOW
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            if (transactionAmountUnprocessed.isZero()) {
                break;
            }
            if (!currentInstallment.isNotFullyPaidOff()) {
                continue;
            }

            // BEFORE DUE DATE
            if (transactionDate.isBefore(currentInstallment.getDueDate())) {

                // Settle any already-levied charges (penalties/fees whose charge due date is on or before the
                // transaction date) on this installment before parking.
                transactionAmountUnprocessed = settleAccruedDueChargesBeforeParking(currentInstallment, loanTransaction,
                        transactionAmountUnprocessed, transactionMappings, charges);

                if (transactionAmountUnprocessed.isGreaterThanZero()) {
                    loanTransaction.updateTransactionMetaData("{\"transactionSubType\":\"EXCESS_SETTLEMENT\"}");
                    loanTransaction.setExcessPayment(transactionAmountUnprocessed);
                    updateTotalExcessPayment(loanTransaction, transactionAmountUnprocessed);
                }

                loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);

                return Money.zero(currency);
            }

            // Due / Late PAYMENT
            if (!currentInstallment.getDueDate().isAfter(transactionDate)) {
                if (DateUtils.isAfter(transactionDate, currentInstallment.getDueDate())) {
                    // Late Payment
                    transactionAmountUnprocessed = handleTransactionThatIsALateRepaymentOfInstallment(currentInstallment, installments,
                            loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                } else {
                    // On-Time Payment
                    transactionAmountUnprocessed = handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment, loanTransaction,
                            transactionAmountUnprocessed, transactionMappings, charges);
                }
            }
        }

        // Remaining amount parked as EXCESS

        if (transactionAmountUnprocessed.isGreaterThanZero()) {

            loanTransaction.updateTransactionMetaData("{\"transactionSubType\":\"EXCESS_SETTLEMENT\"}");
            loanTransaction.setExcessPayment(transactionAmountUnprocessed);

            updateTotalExcessPayment(loanTransaction, transactionAmountUnprocessed);
        }

        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);

        return Money.zero(currency);
    }

    private void updateTotalExcessPayment(final LoanTransaction loanTransaction, final Money excessAmount) {

        if (loanTransaction == null || excessAmount == null || !excessAmount.isGreaterThanZero() || loanTransaction.isReversed()
                || isSystemGeneratedTransaction(loanTransaction)) {
            return;
        }

        loanTransaction.getLoan().addToTotalExcessPaymentAmount(excessAmount);
    }

    private boolean isSystemGeneratedTransaction(final LoanTransaction tx) {

        return tx != null && tx.getTypeOf() == LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT;
    }

    /**
     * Before parking the remainder of a repayment against a not-yet-due installment, settle any charges on that
     * installment that have already been levied (charge due date on or before the transaction date). Such charges -
     * typically accrued/overdue penalties - are current dues even though the installment's principal and interest are
     * not yet payable, so they must be collected rather than diverted to the excess pool. Principal and interest are
     * intentionally left untouched so parking still defers them. Returns the transaction amount still unprocessed after
     * the due charges have been paid.
     */
    private Money settleAccruedDueChargesBeforeParking(final LoanRepaymentScheduleInstallment installment,
            final LoanTransaction loanTransaction, Money amountRemaining,
            final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, final Set<LoanCharge> charges) {
        if (charges == null || charges.isEmpty() || amountRemaining == null || !amountRemaining.isGreaterThanZero()) {
            return amountRemaining;
        }
        final MonetaryCurrency currency = amountRemaining.getCurrency();
        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final Integer installmentNumber = installment.getInstallmentNumber();

        Money duePenaltyOutstanding = Money.zero(currency);
        Money dueFeeOutstanding = Money.zero(currency);
        for (final LoanCharge charge : charges) {
            if (!charge.isActive() || charge.isPaid() || charge.isWaived()) {
                continue;
            }
            final LocalDate chargeDueDate = charge.getDueLocalDate();
            if (chargeDueDate == null || chargeDueDate.isAfter(transactionDate)) {
                continue;
            }
            final Money outstanding = charge.getAmountOutstanding(currency);
            if (!outstanding.isGreaterThanZero()) {
                continue;
            }
            if (charge.isPenaltyCharge()) {
                duePenaltyOutstanding = duePenaltyOutstanding.plus(outstanding);
            } else {
                dueFeeOutstanding = dueFeeOutstanding.plus(outstanding);
            }
        }

        // Never pay more than what is actually outstanding on this installment.
        final Money installmentPenalty = installment.getPenaltyChargesOutstanding(currency);
        final Money installmentFee = installment.getFeeChargesOutstanding(currency);
        final Money payablePenalty = duePenaltyOutstanding.isGreaterThan(installmentPenalty) ? installmentPenalty : duePenaltyOutstanding;
        final Money payableFee = dueFeeOutstanding.isGreaterThan(installmentFee) ? installmentFee : dueFeeOutstanding;
        if (!payablePenalty.isGreaterThanZero() && !payableFee.isGreaterThanZero()) {
            return amountRemaining;
        }

        final Money zero = Money.zero(currency);
        Money penaltyPaid = zero;
        Money feePaid = zero;

        // Penalties first, then fees.
        if (payablePenalty.isGreaterThanZero() && amountRemaining.isGreaterThanZero()) {
            final Money cap = amountRemaining.isGreaterThan(payablePenalty) ? payablePenalty : amountRemaining;
            penaltyPaid = installment.payPenaltyChargesComponent(transactionDate, cap);
            amountRemaining = amountRemaining.minus(penaltyPaid);
            if (penaltyPaid.isGreaterThanZero()) {
                updateChargesPaidAmountBy(loanTransaction, penaltyPaid, extractPenaltyCharges(charges), installmentNumber);
            }
        }
        if (payableFee.isGreaterThanZero() && amountRemaining.isGreaterThanZero()) {
            final Money cap = amountRemaining.isGreaterThan(payableFee) ? payableFee : amountRemaining;
            feePaid = installment.payFeeChargesComponent(transactionDate, cap);
            amountRemaining = amountRemaining.minus(feePaid);
            if (feePaid.isGreaterThanZero()) {
                updateChargesPaidAmountBy(loanTransaction, feePaid, extractFeeCharges(charges), installmentNumber);
            }
        }

        if (penaltyPaid.plus(feePaid).isGreaterThanZero()) {
            loanTransaction.updateComponents(zero, zero, feePaid, penaltyPaid);
            transactionMappings.add(
                    LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment, zero, zero, feePaid, penaltyPaid));
        }
        return amountRemaining;
    }

    protected Set<LoanCharge> extractFeeCharges(final Set<LoanCharge> loanCharges) {
        final Set<LoanCharge> feeCharges = new HashSet<>();
        for (final LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isFeeCharge()) {
                feeCharges.add(loanCharge);
            }
        }
        return feeCharges;
    }

    protected Set<LoanCharge> extractPenaltyCharges(final Set<LoanCharge> loanCharges) {
        final Set<LoanCharge> penaltyCharges = new HashSet<>();
        for (final LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isPenaltyCharge()) {
                penaltyCharges.add(loanCharge);
            }
        }
        return penaltyCharges;
    }

    protected void updateChargesPaidAmountBy(final LoanTransaction loanTransaction, final Money chargeAmount, final Set<LoanCharge> charges,
            final Integer installmentNumber) {

        Money amountRemaining = chargeAmount;
        final RoundingMode taxRoundingMode = TemporaryConfigurationServiceContainer.getTaxRoundingMode();
        final Set<LoanCharge> chargesThatCannotBeFullyPaidByOneInstallment = new HashSet<>();

        while (amountRemaining.isGreaterThanZero()) {
            final LoanCharge unpaidCharge = findEarliestUnpaidChargeFromUnOrderedSet(charges, chargeAmount.getCurrency());
            Money feeAmount = chargeAmount.zero();
            if (loanTransaction.isChargePayment()) {
                feeAmount = chargeAmount;
            }
            if (unpaidCharge == null) {
                break; // All are trache charges
            }

            // If we've already determined this charge cannot be fully paid by one installment, skip it
            if (chargesThatCannotBeFullyPaidByOneInstallment.contains(unpaidCharge)) {
                charges.remove(unpaidCharge);
            }

            final Money amountPaidTowardsCharge = unpaidCharge.updatePaidAmountBy(amountRemaining, installmentNumber, feeAmount);
            if (!amountPaidTowardsCharge.isZero()) {
                Set<LoanChargePaidBy> chargesPaidBies = loanTransaction.getLoanChargesPaid();
                if (loanTransaction.isChargePayment()) {
                    // A multi-charge payment pre-builds one LoanChargePaidBy per (charge, installment) allocation, and
                    // those amounts already sum to the transaction amount. Re-deriving them here is destructive: this
                    // loop walks a running remainder via findEarliestUnpaidCharge and writes each iteration's partial
                    // chunk onto whichever receipt matches by charge id, so a charge whose payment straddles two chunks
                    // is left with only a partial amount and the receipts no longer sum to the transaction amount.
                    // Only the legacy single-charge flow, which pre-builds ONE receipt with the gross transaction
                    // amount, still needs its amount capped to what was actually applied. When there are multiple
                    // receipts (see hasMultipleChargeAllocations) the pre-built allocations are authoritative and must
                    // be left untouched.
                    if (!hasMultipleChargeAllocations(chargesPaidBies) && chargesPaidBies.size() == 1) {
                        final LoanChargePaidBy soleChargePaidBy = chargesPaidBies.iterator().next();
                        final LoanCharge loanCharge = soleChargePaidBy.getLoanCharge();
                        if (loanCharge != null && Objects.equals(loanCharge.getId(), unpaidCharge.getId())) {
                            soleChargePaidBy.setAmount(amountPaidTowardsCharge.getAmount(), taxRoundingMode);
                        }
                    }
                } else {
                    final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(loanTransaction, unpaidCharge,
                            amountPaidTowardsCharge.getAmount(), installmentNumber, taxRoundingMode);
                    chargesPaidBies.add(loanChargePaidBy);
                }
                amountRemaining = amountRemaining.minus(amountPaidTowardsCharge);
            } else {
                chargesThatCannotBeFullyPaidByOneInstallment.add(unpaidCharge);
            }
        }

    }

    public interface ChargesPaidByFunction {

        void accept(LoanTransaction loanTransaction, Money feeCharges, Set<LoanCharge> charges, Integer installmentNumber);
    }

    public ChargesPaidByFunction getChargesPaymentFunction(LoanRepaymentScheduleInstallment.PaymentAction action) {
        return switch (action) {
            case PAY -> this::updateChargesPaidAmountBy;
            case UNPAY -> this::undoChargesPaidAmountBy;
        };
    }

    /**
     * A multi-charge payment pre-builds one {@link LoanChargePaidBy} per (charge, installment) allocation, and those
     * amounts already sum to the transaction amount. When more than one such receipt exists the allocations are
     * authoritative and must be replayed as-is (rather than re-derived from each charge's current amount), so this is
     * the single source of truth for the "multi-charge payment" decision used across reprocessing and charge marking.
     */
    private static boolean hasMultipleChargeAllocations(final Set<LoanChargePaidBy> chargePaidBies) {
        return chargePaidBies.size() > 1;
    }

    /**
     * Resolves the installment a multi-charge-payment receipt applies to: by the receipt's installment number when
     * present (instalment fees), otherwise by the period the charge is due in (regular/overdue charges), mirroring how
     * the allocation's installment was picked when the payment was made. When neither resolves - e.g. an
     * overdue/post-maturity penalty (installment_number = NULL) whose due date falls beyond the last scheduled period,
     * or a schedule that shrank after a reschedule/re-age - it falls back to the latest-due installment (the
     * matured/additional installment that carries such penalties) so the allocation is never silently dropped into
     * overpayment. Returns {@code null} only when there are no installments at all.
     */
    private LoanRepaymentScheduleInstallment findInstallmentForChargePaidBy(final List<LoanRepaymentScheduleInstallment> installments,
            final int firstNormalInstallmentNumber, final LoanChargePaidBy chargePaidBy) {
        final Integer installmentNumber = chargePaidBy.getInstallmentNumber();
        if (installmentNumber != null) {
            for (final LoanRepaymentScheduleInstallment installment : installments) {
                if (installmentNumber.equals(installment.getInstallmentNumber())) {
                    return installment;
                }
            }
        }
        final LoanCharge charge = chargePaidBy.getLoanCharge();
        if (charge != null) {
            for (final LoanRepaymentScheduleInstallment installment : installments) {
                final boolean isFirstPeriod = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
                if (charge.isDueInPeriod(installment.getFromDate(), installment.getDueDate(), isFirstPeriod)) {
                    return installment;
                }
            }
        }
        LoanRepaymentScheduleInstallment latestInstallment = null;
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (latestInstallment == null || DateUtils.isAfter(installment.getDueDate(), latestInstallment.getDueDate())) {
                latestInstallment = installment;
            }
        }
        if (latestInstallment != null) {
            log.warn(
                    "Charge payment reprocess: LoanChargePaidBy (loanChargeId={}, installmentNumber={}, amount={}) matched no installment "
                            + "by number or due period; falling back to latest-due installment [number={}].",
                    charge != null ? charge.getId() : null, installmentNumber, chargePaidBy.getAmount(),
                    latestInstallment.getInstallmentNumber());
        }
        return latestInstallment;
    }

    protected LoanCharge findEarliestUnpaidChargeFromUnOrderedSet(final Set<LoanCharge> charges, final MonetaryCurrency currency) {
        // The receipt for money applied to a charge must always land on the same charge that a charge payment would
        // target, and must be stable across reprocessing. Selecting the outstanding charge with the smallest
        // (effective due date, charge id) - the exact ordering used by the charge payment path
        // (LoanChargeEffectiveDueDateComparator) - makes this deterministic: without the charge-id tiebreak two charges
        // sharing a due date would be picked based on the (unordered) set's iteration order, so the receipt could be
        // attributed to a different same-due-date charge than the one the money actually reduced.
        return charges.stream()
                .filter(loanCharge -> !loanCharge.isDueAtDisbursement() && loanCharge.getAmountOutstanding(currency).isGreaterThanZero())
                .min(LoanChargeEffectiveDueDateComparator.INSTANCE).orElse(null);
    }

    protected void handleWriteOff(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltychargesPortion = Money.zero(currency);

        // determine how much is written off in total and breakdown for
        // principal, interest and charges
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {

            if (currentInstallment.isNotFullyPaidOff()) {
                principalPortion = principalPortion.plus(currentInstallment.writeOffOutstandingPrincipal(transactionDate, currency));
                interestPortion = interestPortion.plus(currentInstallment.writeOffOutstandingInterest(transactionDate, currency));
                feeChargesPortion = feeChargesPortion.plus(currentInstallment.writeOffOutstandingFeeCharges(transactionDate, currency));
                penaltychargesPortion = penaltychargesPortion
                        .plus(currentInstallment.writeOffOutstandingPenaltyCharges(transactionDate, currency));
            }
        }

        loanTransaction.resetDerivedComponents();
        loanTransaction.updateComponentsAndTotal(principalPortion, interestPortion, feeChargesPortion, penaltychargesPortion);
    }

    protected void handleChargeback(LoanTransaction loanTransaction, TransactionCtx ctx) {
        processCreditTransaction(loanTransaction, ctx.getOverpaymentHolder(), ctx.getCurrency(), ctx.getInstallments());
    }

    private void handleChargeOff(LoanTransaction loanTransaction, TransactionCtx transactionCtx) {
        recalculateChargeOffTransaction(transactionCtx.getChangedTransactionDetail(), loanTransaction, transactionCtx.getCurrency(),
                transactionCtx.getInstallments());
    }

    protected void handleCreditBalanceRefund(LoanTransaction loanTransaction, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments, MoneyHolder overpaidAmountHolder) {
        processCreditTransaction(loanTransaction, overpaidAmountHolder, currency, installments);
    }

    protected void handleRefund(LoanTransaction loanTransaction, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges) {
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();
        final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate);
        installments.sort(Collections.reverseOrder(byDate));
        Money transactionAmountUnprocessed = loanTransaction.getAmount(currency);

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            Money outstanding = currentInstallment.getTotalOutstanding(currency);
            Money due = currentInstallment.getDue(currency);

            if (outstanding.isLessThan(due)) {
                transactionAmountUnprocessed = handleRefundTransactionPaymentOfInstallment(currentInstallment, loanTransaction,
                        transactionAmountUnprocessed, transactionMappings);

            }

            if (transactionAmountUnprocessed.isZero()) {
                break;
            }

        }

        final Set<LoanCharge> loanFees = extractFeeCharges(charges);
        final Set<LoanCharge> loanPenalties = extractPenaltyCharges(charges);
        Integer installmentNumber = null;

        final Money feeCharges = loanTransaction.getFeeChargesPortion(currency);
        if (feeCharges.isGreaterThanZero()) {
            undoChargesPaidAmountBy(loanTransaction, feeCharges, loanFees, installmentNumber);
        }

        final Money penaltyCharges = loanTransaction.getPenaltyChargesPortion(currency);
        if (penaltyCharges.isGreaterThanZero()) {
            undoChargesPaidAmountBy(loanTransaction, penaltyCharges, loanPenalties, installmentNumber);
        }
        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
    }

    protected void undoChargesPaidAmountBy(final LoanTransaction loanTransaction, final Money chargeAmount, final Set<LoanCharge> charges,
            final Integer installmentNumber) {

        Money amountRemaining = chargeAmount;
        final RoundingMode taxRoundingMode = TemporaryConfigurationServiceContainer.getTaxRoundingMode();
        while (amountRemaining.isGreaterThanZero()) {
            final LoanCharge paidCharge = findLatestPaidChargeFromUnOrderedSet(charges, chargeAmount.getCurrency());

            // No paid charge left to unwind: stop rather than spin forever on the remaining amount.
            if (paidCharge == null) {
                break;
            }

            Money feeAmount = chargeAmount.zero();

            final Money amountDeductedTowardsCharge = paidCharge.undoPaidOrPartiallyAmountBy(amountRemaining, installmentNumber, feeAmount);

            // No progress made on this pass (nothing could be deducted): break to avoid an infinite loop, since
            // findLatestPaidChargeFromUnOrderedSet would keep returning the same charge with the same zero result.
            if (!amountDeductedTowardsCharge.isGreaterThanZero()) {
                break;
            }

            final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(loanTransaction, paidCharge,
                    amountDeductedTowardsCharge.getAmount().multiply(new BigDecimal(-1)), null, taxRoundingMode);
            loanTransaction.getLoanChargesPaid().add(loanChargePaidBy);

            amountRemaining = amountRemaining.minus(amountDeductedTowardsCharge);
        }

    }

    private LoanCharge findLatestPaidChargeFromUnOrderedSet(final Set<LoanCharge> charges, MonetaryCurrency currency) {
        LoanCharge latestPaidCharge = null;
        LoanCharge installemntCharge = null;
        LoanInstallmentCharge chargePerInstallment = null;
        for (final LoanCharge loanCharge : charges) {
            boolean isPaidOrPartiallyPaid = loanCharge.isPaidOrPartiallyPaid(currency);
            if (isPaidOrPartiallyPaid && !loanCharge.isDueAtDisbursement()) {
                if (loanCharge.isInstalmentFee()) {
                    LoanInstallmentCharge paidLoanChargePerInstallment = loanCharge
                            .getLastPaidOrPartiallyPaidInstallmentLoanCharge(currency);
                    if (chargePerInstallment == null || (paidLoanChargePerInstallment != null
                            && DateUtils.isBefore(chargePerInstallment.getRepaymentInstallment().getDueDate(),
                                    paidLoanChargePerInstallment.getRepaymentInstallment().getDueDate()))) {
                        installemntCharge = loanCharge;
                        chargePerInstallment = paidLoanChargePerInstallment;
                    }
                } else if (latestPaidCharge == null || DateUtils.isAfter(loanCharge.getDueLocalDate(), latestPaidCharge.getDueLocalDate())
                        || (DateUtils.isEqual(loanCharge.getDueLocalDate(), latestPaidCharge.getDueLocalDate())
                                && isHigherChargeId(loanCharge, latestPaidCharge))) {
                    latestPaidCharge = loanCharge;
                }
            }
        }
        if (latestPaidCharge == null || (chargePerInstallment != null
                && DateUtils.isAfter(latestPaidCharge.getDueLocalDate(), chargePerInstallment.getRepaymentInstallment().getDueDate()))) {
            latestPaidCharge = installemntCharge;
        }

        return latestPaidCharge;
    }

    // Deterministic tiebreak for two paid charges that share a due date. The PAY path
    // (findEarliestUnpaidChargeFromUnOrderedSet) settles same-due-date charges in ascending charge id (the lower id was
    // created and paid first). Undo reverses payment order - unwinding the charge paid most recently first - so on a
    // due-date tie it must pick the higher charge id. Without this, the winner depended on the unordered set's
    // iteration
    // order, so an undo could unmark a different same-due-date charge than the one PAY marked.
    private static boolean isHigherChargeId(final LoanCharge candidate, final LoanCharge current) {
        final Long candidateId = candidate.getId();
        final Long currentId = current.getId();
        if (candidateId == null) {
            return false;
        }
        if (currentId == null) {
            return true;
        }
        return candidateId > currentId;
    }

    protected void addChargeOnlyRepaymentInstallmentIfRequired(Set<LoanCharge> charges,
            List<LoanRepaymentScheduleInstallment> installments) {
        LoanCharge latestCharge = getLatestLoanChargeWithSpecificDueDate(charges);
        if (latestCharge == null) {
            return;
        }
        loanChargeProcessor.addChargeOnlyRepaymentInstallmentIfRequired(latestCharge, installments);
    }

    protected LoanCharge getLatestLoanChargeWithSpecificDueDate(Set<LoanCharge> charges) {
        if (charges == null) {
            return null;
        }
        LoanCharge latestCharge = null;
        final List<LoanCharge> chargesWithSpecificDueDate = new ArrayList<>(
                charges.stream().filter(LoanCharge::isSpecifiedDueDate).toList());
        if (!CollectionUtils.isEmpty(chargesWithSpecificDueDate)) {
            chargesWithSpecificDueDate
                    .sort((charge1, charge2) -> DateUtils.compare(charge1.getEffectiveDueDate(), charge2.getEffectiveDueDate()));
            latestCharge = chargesWithSpecificDueDate.getLast();
        }
        return latestCharge;
    }

    private BigDecimal getInterestTillChargeOffForPeriod(final Loan loan, final LocalDate chargeOffDate) {
        BigDecimal interestTillChargeOff = BigDecimal.ZERO;
        final MonetaryCurrency currency = loan.getCurrency();

        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments().stream()
                .filter(i -> !i.isAdditional()).toList();

        for (LoanRepaymentScheduleInstallment installment : installments) {
            final boolean isPastPeriod = !installment.getDueDate().isAfter(chargeOffDate);
            final boolean isInPeriod = !installment.getFromDate().isAfter(chargeOffDate) && installment.getDueDate().isAfter(chargeOffDate);

            BigDecimal interest = BigDecimal.ZERO;

            if (isPastPeriod) {
                interest = installment.getInterestCharged(currency).minus(installment.getCreditedInterest()).getAmount();
            } else if (isInPeriod) {
                final BigDecimal totalInterest = installment.getInterestOutstanding(currency).getAmount();
                if (LoanChargeOffBehaviour.ZERO_INTEREST.equals(loan.getLoanProductRelatedDetail().getChargeOffBehaviour())
                        || LoanChargeOffBehaviour.ACCELERATE_MATURITY.equals(loan.getLoanProductRelatedDetail().getChargeOffBehaviour())) {
                    interest = totalInterest;
                } else {
                    final long totalDaysInPeriod = ChronoUnit.DAYS.between(installment.getFromDate(), installment.getDueDate());
                    final long daysTillChargeOff = ChronoUnit.DAYS.between(installment.getFromDate(), chargeOffDate);
                    final MathContext mc = resolveMathContext(loan);

                    interest = Money.of(currency, totalInterest.divide(BigDecimal.valueOf(totalDaysInPeriod), mc)
                            .multiply(BigDecimal.valueOf(daysTillChargeOff), mc), mc).getAmount();
                }
            }
            interestTillChargeOff = interestTillChargeOff.add(interest);
        }

        return interestTillChargeOff;
    }

    private void createMissingAccrualTransactionDuringChargeOffIfNeeded(final BigDecimal newInterest,
            final LoanTransaction chargeOffTransaction, final LocalDate chargeOffDate,
            final ChangedTransactionDetail changedTransactionDetail) {
        final Loan loan = chargeOffTransaction.getLoan();
        final List<LoanRepaymentScheduleInstallment> relevantInstallments = loan.getRepaymentScheduleInstallments().stream()
                .filter(i -> !i.getFromDate().isAfter(chargeOffDate)).toList();

        if (relevantInstallments.isEmpty()) {
            return;
        }

        final BigDecimal sumOfAccrualsTillChargeOff = loan.getLoanTransactions().stream()
                .filter(lt -> lt.isAccrual() && !lt.getTransactionDate().isAfter(chargeOffDate) && lt.isNotReversed())
                .map(lt -> Optional.ofNullable(lt.getInterestPortion()).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal sumOfAccrualAdjustmentsTillChargeOff = loan.getLoanTransactions().stream()
                .filter(lt -> lt.isAccrualAdjustment() && !lt.getTransactionDate().isAfter(chargeOffDate) && lt.isNotReversed())
                .map(lt -> Optional.ofNullable(lt.getInterestPortion()).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal missingAccrualAmount = newInterest.subtract(sumOfAccrualsTillChargeOff).add(sumOfAccrualAdjustmentsTillChargeOff);

        if (missingAccrualAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        final LoanTransaction newAccrualTransaction;

        if (missingAccrualAmount.compareTo(BigDecimal.ZERO) > 0) {
            newAccrualTransaction = accrueTransaction(loan, loan.getOffice(), chargeOffDate, missingAccrualAmount, missingAccrualAmount,
                    ZERO, ZERO, externalIdFactory.create());
        } else {
            newAccrualTransaction = accrualAdjustment(loan, loan.getOffice(), chargeOffDate, missingAccrualAmount.abs(),
                    missingAccrualAmount.abs(), ZERO, ZERO, externalIdFactory.create());
        }

        changedTransactionDetail.addNewTransactionChangeBeforeExistingOne(new TransactionChangeData(null, newAccrualTransaction),
                chargeOffTransaction);
    }
}
