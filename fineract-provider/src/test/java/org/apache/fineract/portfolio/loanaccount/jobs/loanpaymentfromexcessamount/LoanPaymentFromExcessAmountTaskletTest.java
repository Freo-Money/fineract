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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per loan the nightly job first settles the due dates it missed, in their own transaction, then sweeps what is due on
 * the run date. A business rule refusing the missed dates falls back to the run-date sweep; any other failure is
 * reported and the loan left for the next run. Schedule: two installments of 3,120 due 02-01 and 03-01.
 */
class LoanPaymentFromExcessAmountTaskletTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("INR", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final LocalDate DUE_1 = LocalDate.of(2027, 2, 1);
    private static final LocalDate DUE_2 = LocalDate.of(2027, 3, 1);
    private static final Long LOAN_ID = 1L;

    private static final LocalDate RUN_DATE = LocalDate.of(2027, 2, 3);

    private LoanAccountDomainService domainService;
    private LoanRepositoryWrapper loanRepository;
    private TransactionTemplate transactionTemplate;
    private Loan loan;
    private LoanPaymentFromExcessAmountTasklet tasklet;

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
    }

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);

        loan = Mockito.mock(Loan.class);
        lenient().when(loan.getId()).thenReturn(LOAN_ID);
        lenient().when(loan.getTotalExcessPaymentAmount()).thenReturn(BigDecimal.valueOf(3120));
        lenient().when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        lenient().when(loan.getCurrency()).thenReturn(CURRENCY);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment(1, LocalDate.of(2027, 1, 1), DUE_1),
                installment(2, DUE_1, DUE_2));
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        loanRepository = Mockito.mock(LoanRepositoryWrapper.class);
        lenient().when(loanRepository.getLoanIdsWithExcessAmount(any())).thenReturn(List.of(LOAN_ID));
        lenient().when(loanRepository.findOneWithNotFoundDetection(LOAN_ID)).thenReturn(loan);

        // Each loan runs in its own transaction; the template simply runs the callback here.
        transactionTemplate = Mockito.mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            final Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        domainService = Mockito.mock(LoanAccountDomainService.class);
        tasklet = new LoanPaymentFromExcessAmountTasklet(domainService, loanRepository, transactionTemplate,
                Mockito.mock(ExternalIdFactory.class));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void missedDueDatesAreSettledInTheirOwnTransactionBeforeTheRunDateSweep() throws Exception {
        businessDate(RUN_DATE);

        runJob();

        final InOrder inOrder = Mockito.inOrder(domainService);
        inOrder.verify(domainService).settleMissedParkedExcessDueDates(loan, RUN_DATE);
        inOrder.verify(domainService).sweepParkedExcessDueOn(loan, RUN_DATE, "Auto Payment");
        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(loanRepository, times(2)).findOneWithNotFoundDetection(LOAN_ID);
    }

    @Test
    void aBusinessRuleRefusingTheMissedDatesFallsBackToTheRunDateSweep() throws Exception {
        businessDate(RUN_DATE);
        doThrow(new GeneralPlatformDomainRuleException("error.msg.accounting.closed", "accounting period closed")).when(domainService)
                .settleMissedParkedExcessDueDates(any(), any());

        runJob();

        verify(domainService).sweepParkedExcessDueOn(loan, RUN_DATE, "Auto Payment");
    }

    @Test
    void anyOtherFailureOnTheMissedDatesIsReportedAndTheLoanLeftForTheNextRun() {
        businessDate(RUN_DATE);
        doThrow(new IllegalStateException("optimistic lock")).when(domainService).settleMissedParkedExcessDueDates(any(), any());

        assertThrows(JobExecutionException.class, this::runJob);

        verify(domainService, never()).sweepParkedExcessDueOn(any(), any(), any());
    }

    @Test
    void aRunDateSweepFailureIsReported() {
        businessDate(RUN_DATE);
        doThrow(new IllegalStateException("broken loan")).when(domainService).sweepParkedExcessDueOn(any(), any(), any());

        assertThrows(JobExecutionException.class, this::runJob);

        verify(domainService, times(1)).settleMissedParkedExcessDueDates(loan, RUN_DATE);
        verify(domainService, times(1)).sweepParkedExcessDueOn(any(), any(), any());
    }

    private void runJob() throws Exception {
        tasklet.execute(Mockito.mock(StepContribution.class), Mockito.mock(ChunkContext.class));
    }

    private void businessDate(final LocalDate date) {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final LocalDate from, final LocalDate due) {
        return new LoanRepaymentScheduleInstallment(loan, number, from, due, BigDecimal.valueOf(3000), BigDecimal.valueOf(120),
                BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO);
    }
}
