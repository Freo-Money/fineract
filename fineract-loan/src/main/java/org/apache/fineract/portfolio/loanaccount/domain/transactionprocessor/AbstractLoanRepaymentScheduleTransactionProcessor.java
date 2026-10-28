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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptAllocation;
import org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptInstallmentResolver;
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
        // One aggregated warning per loan at the end of the replay, not one per transaction: a tenant with a backlog
        // of clipped charge payments would otherwise flood the log on every reprocess and every COB run.
        final List<Long> unreconciledChargePayments = new ArrayList<>();
        Money unreconciledGap = Money.zero(currency);
        Long warnLoanId = null;
        // The charges that something other than a charge payment has already settled. Charge payments are all replayed
        // before any of those reach the strategy, so a charge they touch still shows a balance that is about to be
        // reduced again, and no split may be derived from it. Computed at most once per replay, and only when the
        // feature is on: building it forces the lazily-fetched receipts of every repayment-like transaction.
        Set<Long> chargesSettledOutsideChargePayments = null;
        Map<String, Money> settledByKey = null;
        // Both replay-context checks depend only on the loan, so they are answered once instead of per payment.
        Boolean replayingOwnWork = null;
        for (final LoanTransaction loanTransaction : transactionsPostDisbursement) {
            if (loanTransaction.isChargePayment()) {
                List<LoanChargePaidDetail> chargePaidDetails = new ArrayList<>();
                final Set<LoanChargePaidBy> chargePaidBies = loanTransaction.getLoanChargesPaid();
                final Set<LoanCharge> transferCharges = new HashSet<>();
                for (final LoanChargePaidBy chargePaidBy : chargePaidBies) {
                    transferCharges.add(chargePaidBy.getLoanCharge());
                }
                int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber(installments);
                boolean deriveAllocationFromChargeSet = false;
                if (replayingOwnWork == null) {
                    replayingOwnWork = isReplayingLoanOwnTransactions(loanTransaction, transactionsPostDisbursement)
                            && isReplayingLoanOwnSchedule(loanTransaction.getLoan(), installments);
                }
                if (hasMultipleChargeAllocations(chargePaidBies) && replayingOwnWork && isChargeSetAllocationAvailable(loanTransaction)) {
                    if (chargesSettledOutsideChargePayments == null) {
                        chargesSettledOutsideChargePayments = chargesSettledByOtherTransactionTypes(transactionsPostDisbursement);
                    }
                    deriveAllocationFromChargeSet = !touchesAnyOf(loanTransaction, chargesSettledOutsideChargePayments);
                    if (!deriveAllocationFromChargeSet) {
                        log.debug(
                                "Charge payment reprocess: charge-set allocation skipped for transaction [id={}]; another transaction "
                                        + "has already settled part of the same charges, which this replay order cannot see yet.",
                                loanTransaction.getId());
                    }
                }
                if (hasMultipleChargeAllocations(chargePaidBies)) {
                    if (!deriveAllocationFromChargeSet && Boolean.TRUE.equals(replayingOwnWork)) {
                        // Only a real replay reports a backlog: the prepayment and foreclosure quotes come through
                        // here too, and a read-only amount request must not log a repair instruction per call.
                        final Money receiptsTotal = loanTransaction.getLoanChargesPaidTotal(currency);
                        if (!receiptsTotal.isEqualTo(loanTransaction.getAmount(currency))) {
                            warnLoanId = loanTransaction.getLoan() == null ? warnLoanId : loanTransaction.getLoan().getId();
                            unreconciledChargePayments.add(loanTransaction.getId());
                            unreconciledGap = unreconciledGap.plus(loanTransaction.getAmount(currency).minus(receiptsTotal));
                        }
                    }
                    if (deriveAllocationFromChargeSet && settledByKey == null) {
                        settledByKey = ChargePaymentReceiptAllocation.settledByKey(transactionsPostDisbursement, currency);
                    }
                    chargePaidDetails.addAll(buildMultiReceiptChargePaidDetails(loanTransaction, currency, installments,
                            firstNormalInstallmentNumber, chargePaidBies, deriveAllocationFromChargeSet, settledByKey));
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
                final IdentityHashMap<LoanChargePaidBy, Money> appliedByReceipt = new IdentityHashMap<>();
                for (LoanChargePaidDetail chargePaidDetail : chargePaidDetails) {
                    if (!unprocessed.isGreaterThanZero()) {
                        break;
                    }
                    Money processAmt = chargePaidDetail.getAmount();
                    if (processAmt.isGreaterThan(unprocessed)) {
                        processAmt = unprocessed;
                    }
                    if (!processAmt.isGreaterThanZero()) {
                        // Nothing to apply: with charge-set allocation this is a receipt whose charge is already fully
                        // settled, and its stored amount is reconciled to zero below.
                        continue;
                    }
                    final List<LoanRepaymentScheduleInstallment> processInstallments = new ArrayList<>(1);
                    processInstallments.add(chargePaidDetail.getInstallment());
                    Money chargeLeftover = handleTransactionAndCharges(loanTransaction, currency, processInstallments, transferCharges,
                            processAmt, chargePaidDetail.isFeeCharge(), chargePaidDetail.getReceipt());
                    final LoanChargePaidBy processedReceipt = chargePaidDetail.getReceipt();
                    if (processedReceipt != null) {
                        appliedByReceipt.merge(processedReceipt, processAmt.minus(chargeLeftover), Money::plus);
                    }
                    unprocessed = unprocessed.minus(processAmt).plus(chargeLeftover);
                }

                if (unprocessed.isGreaterThanZero()) {
                    onLoanOverpayment(loanTransaction, unprocessed);
                    loanTransaction.setOverPayments(unprocessed);
                }

                if (deriveAllocationFromChargeSet) {
                    rewriteReceiptAmountsToAppliedAllocation(loanTransaction, currency, chargePaidDetails, appliedByReceipt);
                    final Money unreconciled = unreconciledAmountOf(loanTransaction, currency);
                    if (unreconciled.isGreaterThanZero()) {
                        warnLoanId = loanTransaction.getLoan() == null ? warnLoanId : loanTransaction.getLoan().getId();
                        unreconciledChargePayments.add(loanTransaction.getId());
                        unreconciledGap = unreconciledGap.plus(unreconciled);
                    }
                }

            } else {
                transactionsToBeProcessed.add(loanTransaction);
            }
        }

        if (!unreconciledChargePayments.isEmpty()) {
            log.warn(
                    "Charge payment reprocess: on loan [id={}] {} charge payment(s) did not reconcile (total {} unallocated); that money "
                            + "falls through to overpayment on every reprocess until the receipts are repaired "
                            + "(repairChargePaymentReceipts loan command). Transactions: {}.",
                    warnLoanId, unreconciledChargePayments.size(), unreconciledGap.getAmount(), unreconciledChargePayments);
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
        return handleTransactionAndCharges(loanTransaction, currency, installments, charges, chargeAmountToProcess, isFeeCharge, null);
    }

    /**
     * @param receipt
     *            the stored {@link LoanChargePaidBy} this per-allocation call replays, or null when the allocation was
     *            derived rather than replayed. When present, the charge it names is marked by exactly what this call
     *            applied to the installment, so charge-level paid amounts follow the receipts (the authoritative
     *            allocation) instead of a global earliest-unpaid walk that can land on a different charge of the same
     *            definition.
     */
    protected Money handleTransactionAndCharges(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, final Money chargeAmountToProcess,
            final boolean isFeeCharge, final LoanChargePaidBy receipt) {
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
                final Money appliedDelta = isFeeCharge ? loanTransaction.getFeeChargesPortion(currency).minus(feeChargesBefore)
                        : loanTransaction.getPenaltyChargesPortion(currency).minus(penaltyChargesBefore);
                if (appliedDelta.isGreaterThanZero()) {
                    if (receipt != null) {
                        markChargePaidByReceipt(loanTransaction, receipt, appliedDelta, installmentNumber);
                    } else {
                        updateChargesPaidAmountBy(loanTransaction, appliedDelta, isFeeCharge ? loanFees : loanPenalties, installmentNumber);
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
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            if (transactionAmountUnprocessed.isGreaterThanZero()) {
                if (currentInstallment.isNotFullyPaidOff()) {
                    if (isTransactionInAdvanceOfInstallment(installmentIndex, installments, transactionDate)) {
                        transactionAmountUnprocessed = handleTransactionThatIsPaymentInAdvanceOfInstallment(currentInstallment,
                                installments, loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                    } else if (isTransactionALateRepaymentOnInstallment(installmentIndex, installments, transactionDate)) {
                        transactionAmountUnprocessed = handleTransactionThatIsALateRepaymentOfInstallment(currentInstallment, installments,
                                loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
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

    /**
     * Marks the receipt's own charge by what this allocation applied to the installment. Inactive charges are never
     * marked (a receipt can outlive its charge when an overdue penalty is deactivated and replayed); the amount still
     * sits on the installment, and the mismatch is logged for the repair tooling.
     */
    private void markChargePaidByReceipt(final LoanTransaction loanTransaction, final LoanChargePaidBy receipt, final Money appliedAmount,
            final Integer installmentNumber) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        final Long loanId = loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null;
        if (!loanCharge.isActive()) {
            log.warn(
                    "Charge payment reprocess: receipt [id={}] of transaction [id={}] on loan [id={}] points to a deactivated charge "
                            + "[id={}]; {} was applied to the installment but no charge was marked paid.",
                    receipt.getId(), loanTransaction.getId(), loanId, loanCharge.getId(), appliedAmount);
            return;
        }
        // installmentNumber is the installment this allocation was actually applied to (already resolved, with
        // fallback, from the receipt). The receipt's own stored number is deliberately NOT used here: a stale number
        // (schedule shrunk after the payment) would make updatePaidAmountBy look up a missing instalment-fee bucket.
        // The RESOLVED number can miss too - the fallback picks the latest-due installment, which an instalment fee
        // need not cover - and LoanCharge.updatePaidAmountBy dereferences that bucket without checking, so verify it
        // exists first.
        if (loanCharge.isInstalmentFee() && installmentNumber != null && loanCharge.getInstallmentLoanCharge(installmentNumber) == null) {
            log.warn(
                    "Charge payment reprocess: receipt [id={}] of transaction [id={}] on loan [id={}] resolved to installment {}, which "
                            + "instalment-fee charge [id={}] has no bucket for; {} was applied to the installment but no charge was "
                            + "marked paid.",
                    receipt.getId(), loanTransaction.getId(), loanId, installmentNumber, loanCharge.getId(), appliedAmount);
            return;
        }
        // Charge-payment convention (see updateChargesPaidAmountBy): the instalment-fee amount hint is the amount
        // applied.
        final Money marked = loanCharge.updatePaidAmountBy(appliedAmount, installmentNumber, appliedAmount);
        if (marked.isLessThan(appliedAmount)) {
            log.warn(
                    "Charge payment reprocess: receipt [id={}] of transaction [id={}] on loan [id={}] applied {} to the installment but "
                            + "charge [id={}] could only absorb {}; charge-level and installment-level paid amounts now differ by {}.",
                    receipt.getId(), loanTransaction.getId(), loanId, appliedAmount, loanCharge.getId(), marked,
                    appliedAmount.minus(marked));
        }
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
     * Builds one allocation per receipt of a multi-receipt charge payment.
     * <p>
     * With {@code deriveAllocationFromChargeSet} the receipts' CHARGES are authoritative and their amounts are
     * re-derived: the transaction amount is spread over those charges in effective-due-date order, each taking at most
     * what it still has outstanding at this point of the replay. Charges are reset before the replay, so a charge an
     * earlier CHARGE PAYMENT already settled offers no room and the money moves on to the next charge of the set.
     * Repayments cannot compete for that room: every charge payment is replayed in this loop before any of them is
     * handed to the strategy, so a charge payment always has first claim on the charges it names. This reproduces how
     * the charge-definition payment API allocated the money in the first place, so a receipt whose amount an earlier
     * destructive re-derivation clipped in place is restored instead of leaving the difference to fall through to
     * overpayment. The split is a pure function of the transaction amount, the charge set and the replay order, so a
     * second reprocess is a no-op.
     * <p>
     * Without the flag the stored receipt amounts are replayed verbatim (the behaviour PR #153 introduced), and only
     * the warning about receipts that do not sum to the transaction amount is emitted.
     */
    private List<LoanChargePaidDetail> buildMultiReceiptChargePaidDetails(final LoanTransaction loanTransaction,
            final MonetaryCurrency currency, final List<LoanRepaymentScheduleInstallment> installments,
            final int firstNormalInstallmentNumber, final Set<LoanChargePaidBy> chargePaidBies, final boolean deriveAllocationFromChargeSet,
            final Map<String, Money> settledByKey) {
        // Sorted only when the amounts are being derived: the order decides which receipt is raised first. Replaying
        // the stored amounts needs no order, and imposing one there would change which receipt a release build
        // truncates when a receipt set already sums above its transaction.
        final Collection<LoanChargePaidBy> receipts = deriveAllocationFromChargeSet
                ? ChargePaymentReceiptAllocation.sortedForAllocation(chargePaidBies, installments)
                : chargePaidBies;
        // The gap is the only money a derived replay may move: each receipt is RAISED towards its cap, never lowered
        // and never re-split, so a payment whose receipts already sum to its amount comes out untouched and no payment
        // can claim room another payment's receipts account for.
        Map<LoanChargePaidBy, Money> topUps = Map.of();
        if (deriveAllocationFromChargeSet) {
            // The cap is measured exactly as the planner measures it, from the stored receipts of every other live
            // transaction, charge payments included. Sizing it from the charge's running paid amount instead would let
            // the payment replayed first claim room a later payment's receipts already account for, and would persist
            // a split that never existed.
            final Map<String, Money> carried = ChargePaymentReceiptAllocation.carriedByKey(loanTransaction, currency);
            topUps = ChargePaymentReceiptAllocation.topUps(List.copyOf(receipts),
                    loanTransaction.getAmount(currency).minus(loanTransaction.getLoanChargesPaidTotal(currency)), currency,
                    receipt -> ChargePaymentReceiptAllocation.capOf(receipt, currency, settledByKey, carried));
        }
        final List<LoanChargePaidDetail> chargePaidDetails = new ArrayList<>(receipts.size());
        for (final LoanChargePaidBy chargePaidBy : receipts) {
            final LoanCharge loanCharge = chargePaidBy.getLoanCharge();
            final LoanRepaymentScheduleInstallment installment = findInstallmentForChargePaidBy(installments, firstNormalInstallmentNumber,
                    chargePaidBy);
            if (installment == null || loanCharge == null) {
                // Only reachable when the loan has no installments at all, or when a receipt lost its charge; surface
                // it rather than silently diverting the allocation to overpayment.
                log.warn(
                        "Charge payment reprocess: could not map LoanChargePaidBy [id={}] (loanChargeId={}, amount={}) on transaction "
                                + "[id={}] of loan [id={}] to any installment; its amount will fall through to overpayment.",
                        chargePaidBy.getId(), loanCharge != null ? loanCharge.getId() : null, chargePaidBy.getAmount(),
                        loanTransaction.getId(), loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null);
                continue;
            }
            Money amount = Money.of(currency, chargePaidBy.getAmount());
            if (deriveAllocationFromChargeSet) {
                amount = amount.plus(topUps.getOrDefault(chargePaidBy, Money.zero(currency)));
            }
            final boolean derived = deriveAllocationFromChargeSet;
            // The receipt is handed down only where its amount was derived here: that is what switches charge marking
            // to the receipt's own charge, so with the flag off the release behaviour stays bit-for-bit unchanged, and
            // a receipt whose amount was not derived is never rewritten either.
            chargePaidDetails.add(new LoanChargePaidDetail(amount, installment, loanCharge.isFeeCharge(), derived ? chargePaidBy : null));
        }
        return chargePaidDetails;
    }

    /**
     * Charge-set allocation is opt-in (global configuration {@code charge-payment-charge-set-allocation}, disabled by
     * default) and is additionally skipped when the product has accounting enabled: a charge payment's journal entries
     * are posted per receipt (tax exclusive), so re-deriving the receipts here would leave the entries already posted
     * inconsistent with nothing to re-post them.
     */
    private boolean isChargeSetAllocationAvailable(final LoanTransaction loanTransaction) {
        if (!TemporaryConfigurationServiceContainer.isChargePaymentChargeSetAllocationEnabled()) {
            return false;
        }
        final Loan loan = loanTransaction.getLoan();
        if (loan == null || loan.getLoanProduct() == null) {
            return false;
        }
        if (!loan.getLoanProduct().isAccountingDisabled()) {
            log.debug(
                    "Charge payment reprocess: charge-set allocation skipped for transaction [id={}] of loan [id={}]; product [id={}] "
                            + "has accounting enabled and its journal entries are derived from the receipts.",
                    loanTransaction.getId(), loan.getId(), loan.getLoanProduct().getId());
            return false;
        }
        if (ChargePaymentReceiptAllocation.hasMixedComponents(loanTransaction.getLoanChargesPaid())) {
            // One transaction pays one component: the installment allocation routes every receipt by the
            // transaction's own fee/penalty flag, so in a mixed set a receipt would be raised on disk while its charge
            // received nothing. The repair planner refuses the same shape.
            log.debug("Charge payment reprocess: charge-set allocation skipped for transaction [id={}] of loan [id={}]; its receipts mix "
                    + "fee and penalty charges.", loanTransaction.getId(), loan.getId());
            return false;
        }
        for (final LoanChargePaidBy receipt : loanTransaction.getLoanChargesPaid()) {
            if (!ChargePaymentReceiptAllocation.isDerivable(receipt)) {
                // One receipt that cannot be sized would push its share onto a sibling charge, leaving the stored
                // receipts summing above the transaction, or leave the whole payment unallocated. Replay the set as it
                // stands; the repair planner reports the same shape for review.
                log.debug(
                        "Charge payment reprocess: charge-set allocation skipped for transaction [id={}] of loan [id={}]; receipt "
                                + "[id={}] cannot be re-derived (inactive, disbursement-time or unmapped instalment-fee charge).",
                        loanTransaction.getId(), loan.getId(), receipt.getId());
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the transaction being replayed is the loan's own managed instance rather than a copy. A copy shares the
     * real {@link LoanChargePaidBy} objects but receives the derived portions itself, so anything written to the
     * receipts from such a replay would never match the real transaction.
     */
    private static boolean isReplayingLoanOwnTransactions(final LoanTransaction loanTransaction,
            final List<LoanTransaction> replayedTransactions) {
        final Loan loan = loanTransaction.getLoan();
        if (loan == null || CollectionUtils.isEmpty(replayedTransactions)) {
            return false;
        }
        for (final LoanTransaction own : loan.getLoanTransactions()) {
            if (own == loanTransaction) {
                return true;
            }
        }
        return false;
    }

    /** The charges that a live transaction OTHER than a charge payment has settled with money. */
    private static Set<Long> chargesSettledByOtherTransactionTypes(final List<LoanTransaction> transactions) {
        final Set<Long> charges = new HashSet<>();
        if (CollectionUtils.isEmpty(transactions)) {
            return charges;
        }
        for (final LoanTransaction transaction : transactions) {
            if (transaction.isChargePayment() || !ChargePaymentReceiptAllocation.isCashSettlementOfCharge(transaction)) {
                continue;
            }
            for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
                if (receipt.getLoanCharge() != null) {
                    charges.add(receipt.getLoanCharge().getId());
                }
            }
        }
        return charges;
    }

    /** Whether any charge this transaction's receipts name appears in the given set. */
    private static boolean touchesAnyOf(final LoanTransaction loanTransaction, final Set<Long> charges) {
        if (charges.isEmpty()) {
            return false;
        }
        for (final LoanChargePaidBy receipt : loanTransaction.getLoanChargesPaid()) {
            if (receipt.getLoanCharge() != null && charges.contains(receipt.getLoanCharge().getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the installments being replayed are the loan's own schedule rows. The prepayment/foreclosure quoting
     * path passes a simulated schedule instead, and nothing persistent may be re-derived from that.
     */
    private static boolean isReplayingLoanOwnSchedule(final Loan loan, final List<LoanRepaymentScheduleInstallment> installments) {
        final List<LoanRepaymentScheduleInstallment> ownInstallments = loan.getRepaymentScheduleInstallments();
        if (ownInstallments == installments) {
            return true;
        }
        if (CollectionUtils.isEmpty(installments) || CollectionUtils.isEmpty(ownInstallments)) {
            return false;
        }
        final Set<LoanRepaymentScheduleInstallment> ownByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        ownByIdentity.addAll(ownInstallments);
        return ownByIdentity.containsAll(installments);
    }

    /**
     * Writes a receipt's amount to what the replay actually applied for it, so the stored receipts sum to the
     * transaction's allocated portions. Changing an amount re-derives that receipt's tax details, so the product's tax
     * rounding mode is used.
     * <p>
     * A receipt is rewritten only when it was allocated something AND the installment absorbed that allocation in full.
     * Anything else leaves the stored amount alone, because lowering it would destroy the only record of what was
     * collected for that charge and no later replay could recover it: a receipt whose charge is inactive or already
     * settled by another transaction keeps its amount and is reported instead (the planner classifies those for
     * review), and so does one the installment bucket could not take in full, or one that resolved to no installment, A
     * receipt whose amount this replay did not derive, such as one on a disbursement-time charge, never reaches this
     * method at all.
     */
    private void rewriteReceiptAmountsToAppliedAllocation(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanChargePaidDetail> chargePaidDetails, final IdentityHashMap<LoanChargePaidBy, Money> appliedByReceipt) {
        // identity, not equals: LoanChargePaidBy inherits no equals/hashCode, and two unsaved receipts must stay
        // distinct
        final IdentityHashMap<LoanChargePaidBy, Money> plannedByReceipt = new IdentityHashMap<>();
        for (final LoanChargePaidDetail chargePaidDetail : chargePaidDetails) {
            if (chargePaidDetail.getReceipt() != null) {
                plannedByReceipt.merge(chargePaidDetail.getReceipt(), chargePaidDetail.getAmount(), Money::plus);
            }
        }
        // The same resolution the path that CREATED these receipts uses
        // (LoanChargeService#handleChargePaidTransaction),
        // and the one the legacy single-receipt rewrite above uses: a receipt rewritten here must carry the very tax
        // split a receipt of that amount would be created with, or repairing a loan would re-rate its GST.
        final RoundingMode taxRoundingMode = TemporaryConfigurationServiceContainer.getTaxRoundingMode();
        for (final Map.Entry<LoanChargePaidBy, Money> entry : plannedByReceipt.entrySet()) {
            final LoanChargePaidBy chargePaidBy = entry.getKey();
            final LoanCharge loanCharge = chargePaidBy.getLoanCharge();
            final Money applied = appliedByReceipt.getOrDefault(chargePaidBy, Money.zero(currency));
            if (!applied.isGreaterThanZero()) {
                if (chargePaidBy.getAmount() == null || chargePaidBy.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                log.warn(
                        "Charge payment reprocess: receipt [id={}] (loanChargeId={}) of transaction [id={}] on loan [id={}] could be "
                                + "allocated nothing (its charge is inactive, or already settled); its stored amount of {} is left "
                                + "unchanged and that money falls through to overpayment.",
                        chargePaidBy.getId(), loanCharge != null ? loanCharge.getId() : null, loanTransaction.getId(),
                        loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null, chargePaidBy.getAmount());
                continue;
            }
            if (!applied.isEqualTo(entry.getValue())) {
                log.warn(
                        "Charge payment reprocess: the installment absorbed only {} of the {} planned for receipt [id={}] "
                                + "(loanChargeId={}) on transaction [id={}] of loan [id={}]; the stored receipt amount is left "
                                + "unchanged and the difference falls through to overpayment.",
                        applied.getAmount(), entry.getValue().getAmount(), chargePaidBy.getId(),
                        loanCharge != null ? loanCharge.getId() : null, loanTransaction.getId(),
                        loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null);
                continue;
            }
            final BigDecimal before = chargePaidBy.getAmount();
            if (before != null && before.compareTo(applied.getAmount()) == 0) {
                continue;
            }
            chargePaidBy.setAmount(applied.getAmount(), taxRoundingMode);
            log.info(
                    "Charge payment reprocess: receipt [id={}] (loanChargeId={}) of transaction [id={}] on loan [id={}] re-derived from "
                            + "{} to {}.",
                    chargePaidBy.getId(), chargePaidBy.getLoanCharge() != null ? chargePaidBy.getLoanCharge().getId() : null,
                    loanTransaction.getId(), loanTransaction.getLoan() != null ? loanTransaction.getLoan().getId() : null, before,
                    applied.getAmount());
        }
    }

    /**
     * How much of a charge payment this replay could not place on a charge: the part of its amount that no charge
     * absorbed. Reported once per loan by the caller rather than per transaction, because a tenant with a backlog would
     * otherwise have the log flooded on every reprocess and every COB run.
     */
    private static Money unreconciledAmountOf(final LoanTransaction loanTransaction, final MonetaryCurrency currency) {
        final Money amount = loanTransaction.getAmount(currency);
        final Money portions = loanTransaction.getFeeChargesPortion(currency).plus(loanTransaction.getPenaltyChargesPortion(currency));
        return MathUtil.negativeToZero(amount.minus(portions));
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
        final LoanRepaymentScheduleInstallment resolved = ChargePaymentReceiptInstallmentResolver.resolve(installments,
                firstNormalInstallmentNumber, chargePaidBy);
        if (resolved != null) {
            return resolved;
        }
        final Integer installmentNumber = chargePaidBy.getInstallmentNumber();
        final LoanCharge charge = chargePaidBy.getLoanCharge();
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
