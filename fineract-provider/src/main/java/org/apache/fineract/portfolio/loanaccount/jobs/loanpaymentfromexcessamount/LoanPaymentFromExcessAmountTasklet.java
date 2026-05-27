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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
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

@RequiredArgsConstructor
public class LoanPaymentFromExcessAmountTasklet implements Tasklet {

    private final LoanAccountDomainService loanAccountDomainService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        LocalDate businessDate = LocalDate.now(ZoneId.systemDefault());
        var loans = loanRepositoryWrapper.getLoansWithExcessAmount(businessDate);
        List<Throwable> exceptions = new ArrayList<>();

        for (Loan loan : loans) {
            if (!loan.getStatus().isActive()) {
                continue;
            }
            final Loan currentLoan = loan;
            try {
                BigDecimal totalExcessAmount = loan.getTotalExcessPaymentAmount();

                boolean hasDueInstallment = currentLoan.getRepaymentScheduleInstallments().stream().anyMatch(
                        inst -> !inst.isObligationsMet() && inst.getTotalOutstanding(currentLoan.getCurrency()).isGreaterThanZero()
                                && !inst.getDueDate().isAfter(businessDate));

                if (!hasDueInstallment) {
                    continue;
                }

                if (totalExcessAmount == null || totalExcessAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                List<LoanRepaymentScheduleInstallment> sortedInstallments = currentLoan.getRepaymentScheduleInstallments().stream()
                        .sorted(Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate)).toList();

                for (LoanRepaymentScheduleInstallment dueInstallment : sortedInstallments) {

                    if (totalExcessAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    if (!dueInstallment.isObligationsMet() && dueInstallment.getTotalOutstanding(loan.getCurrency()).isGreaterThanZero()
                            && !dueInstallment.getDueDate().isAfter(businessDate)) {

                        BigDecimal outstandingAmount = dueInstallment.getTotalOutstanding(loan.getCurrency()).getAmount();

                        BigDecimal paymentAmount = totalExcessAmount.compareTo(outstandingAmount) >= 0 ? outstandingAmount
                                : totalExcessAmount;

                        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }

                        try {
                            loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT, loan,
                                    dueInstallment.getDueDate(), paymentAmount, null, "AUTO_EXCESS_PAYMENT",
                                    new ExternalId(UUID.randomUUID().toString()), false, null, false, null, false);
                        } catch (Exception e) {
                            exceptions.add(e);
                            continue;
                        }

                        totalExcessAmount = totalExcessAmount.subtract(paymentAmount);
                        loan.setTotalExcessPaymentAmount(totalExcessAmount);
                    }
                }
            } catch (Exception e) {
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty()) {
            throw new JobExecutionException(exceptions);
        }

        return RepeatStatus.FINISHED;
    }
}
