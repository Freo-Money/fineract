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

package org.apache.fineract.portfolio.loanaccount.jobs.loanpaymentfromexcessamount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.support.TransactionTemplate;

@RequiredArgsConstructor
@Slf4j
public class LoanPaymentFromExcessAmountTasklet implements Tasklet {

    private final LoanAccountDomainService loanAccountDomainService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        // Collect ids only; each loan is then loaded and processed in its own REQUIRES_NEW transaction so one
        // failing loan neither rolls back the others nor poisons a shared persistence context.
        final List<Long> loanIds = loanRepositoryWrapper.getLoansWithExcessAmount(businessDate).stream().map(Loan::getId).toList();
        final List<Throwable> exceptions = new ArrayList<>();

        for (final Long loanId : loanIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> applyExcessForLoan(loanId, businessDate));
            } catch (Exception e) {
                log.error("Loan Payment From Excess Amount failed for loan {}", loanId, e);
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty()) {
            throw new JobExecutionException(exceptions);
        }

        return RepeatStatus.FINISHED;
    }

    private void applyExcessForLoan(final Long loanId, final LocalDate businessDate) {
        final Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
        if (!loan.getStatus().isActive()) {
            return;
        }

        final List<LoanRepaymentScheduleInstallment> sortedInstallments = loan.getRepaymentScheduleInstallments().stream()
                .sorted(Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate)).toList();

        for (int i = 0; i < sortedInstallments.size(); i++) {
            final LoanRepaymentScheduleInstallment dueInstallment = sortedInstallments.get(i);
            final BigDecimal totalExcessAmount = loan.getTotalExcessPaymentAmount();
            if (totalExcessAmount == null || totalExcessAmount.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            if (!loan.getStatus().isActive()) {
                break;
            }
            if (!dueInstallment.isObligationsMet() && dueInstallment.getTotalOutstanding(loan.getCurrency()).isGreaterThanZero()
                    && !dueInstallment.getDueDate().isAfter(businessDate)) {

                final BigDecimal outstandingAmount = dueInstallment.getTotalOutstanding(loan.getCurrency()).getAmount();

                // On the final outstanding installment, draw the ENTIRE remaining pool rather than capping at the
                // outstanding amount: the transaction processor applies what is due and returns the surplus as the
                // sweep's overpayment portion, which is what posts the reclassification journal entry (debit
                // parking liability, credit overpayment liability). Capping here would convert the surplus to
                // overpaid in loan state only, leaving the parking liability overstated and the overpayment
                // liability unfunded when a credit balance refund is paid out.
                boolean hasLaterOutstandingInstallment = false;
                for (int j = i + 1; j < sortedInstallments.size(); j++) {
                    final LoanRepaymentScheduleInstallment later = sortedInstallments.get(j);
                    if (!later.isObligationsMet() && later.getTotalOutstanding(loan.getCurrency()).isGreaterThanZero()) {
                        hasLaterOutstandingInstallment = true;
                        break;
                    }
                }
                final BigDecimal paymentAmount;
                if (hasLaterOutstandingInstallment) {
                    paymentAmount = totalExcessAmount.compareTo(outstandingAmount) >= 0 ? outstandingAmount : totalExcessAmount;
                } else {
                    paymentAmount = totalExcessAmount;
                }

                if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT, loan, dueInstallment.getDueDate(),
                        paymentAmount, null, "Auto Payment", null, false, null, false, null, false);
            }
        }
    }
}
