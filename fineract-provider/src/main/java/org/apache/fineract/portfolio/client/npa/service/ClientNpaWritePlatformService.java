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
package org.apache.fineract.portfolio.client.npa.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.client.ClientEnteredNpaBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.client.ClientExitedNpaBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.client.ClientTriggerLoanChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.jobs.service.updatenpa.LoanNpaUpdateService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.LoanTransactionBeforeClientNpaStartException;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpa;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaHistory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaHistoryRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoan;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanHistory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanHistoryRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaMovementType;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanproduct.domain.ClientNpaExitStrategy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Client-level NPA propagates to all active loans by setting {@code loan.is_npa = true}, so existing loan-level NPA
 * consumers (provisioning, delinquency, accrual suspense, penalties, etc.) apply without separate effective-NPA checks.
 * {@code fr_client_npa_status} tracks client state, trigger loan, exit strategy, and history. {@code enable-client-npa}
 * gates automatic entry/exit/re-mapping only; while it is off, stored client NPA stays effective and there is no manual
 * exit — the flag must be turned back on for a client to leave NPA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ClientNpaWritePlatformService {

    private final ClientNpaRepository clientNpaRepository;
    private final ClientNpaHistoryRepository clientNpaHistoryRepository;
    private final ClientNpaLoanRepository clientNpaLoanRepository;
    private final ClientNpaLoanHistoryRepository clientNpaLoanHistoryRepository;
    private final LoanRepository loanRepository;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final ClientRepositoryWrapper clientRepositoryWrapper;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final JdbcTemplate jdbcTemplate;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanNpaUpdateService loanNpaUpdateService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Client is treated as NPA when {@code fr_client_npa_status.is_npa} is true. The {@code enable-client-npa} flag
     * gates automatic entry/exit/re-mapping only — existing client NPA remains effective while it is off.
     */
    public boolean isClientNpa(final Long clientId) {
        if (clientId == null) {
            return false;
        }
        return clientNpaRepository.existsByClientIdAndNpaTrue(clientId);
    }

    /**
     * When the loan's client is in client-level NPA, rejects a repayment-like transaction dated before the client's NPA
     * start date (the client entered NPA after that date) — such a backdated transaction would rewrite pre-NPA history
     * while the client is currently NPA. Transactions dated on/after the NPA start date fall within the NPA period and
     * are allowed (processed as NPA payments). No-op when the client is not in NPA or has no recorded start date.
     * <p>
     * Only active loans are guarded: contagion maps active loans alone, so a written-off loan's recovery repayment or a
     * closed/overpaid loan's refund is outside the NPA period being protected and must not be blocked by a sibling loan
     * having dragged the client into NPA.
     */
    public void validateTransactionNotBeforeClientNpaStart(final Loan loan, final LocalDate transactionDate) {
        if (loan == null || loan.getClientId() == null || transactionDate == null || !loan.getStatus().isActive()) {
            return;
        }
        clientNpaRepository.findByClientId(loan.getClientId()).filter(ClientNpa::isNpa).ifPresent(clientNpa -> {
            final LocalDate npaStartDate = clientNpa.getNpaStartDate();
            if (npaStartDate != null && transactionDate.isBefore(npaStartDate)) {
                throw new LoanTransactionBeforeClientNpaStartException(loan.getClientId(), loan.getId(), transactionDate, npaStartDate);
            }
        });
    }

    /**
     * Propagates client NPA to a newly disbursed loan when the client is already client-NPA. Runs even if
     * {@code enable-client-npa} is off, because stored client NPA remains effective while it is off.
     */
    public void onLoanDisbursed(final Loan loan) {
        if (loan == null || loan.getClientId() == null) {
            return;
        }
        // Lock before reading, as every other mutator does: an unlocked check-then-act races a concurrent exit
        // (reEvaluateClient), which cannot see this still-uncommitted loan as active. The loan
        // would be left contagion-flagged and mapped under a client that is no longer NPA — drift the nightly
        // reconcile detects but does not repair, since reEvaluateClient returns early for a non-NPA client.
        if (clientNpaRepository.findByClientIdForUpdate(loan.getClientId()).filter(ClientNpa::isNpa).isEmpty()) {
            return;
        }
        mapLoanUnderClientNpa(loan.getClientId(), loan, DateUtils.getBusinessLocalDate());
        syncActiveLoansToClientNpa(List.of(loan));
    }

    /**
     * Always cleans client-NPA loan mapping/history. Auto-exit re-evaluation runs only when {@code enable-client-npa}
     * is on (same gate as COB/reconcile). Safe to call more than once for the same close — mapping delete and
     * {@link #reEvaluateClient} are idempotent.
     */
    public void onLoanClosed(final Loan loan) {
        if (loan == null || loan.getClientId() == null) {
            return;
        }
        final Long clientId = loan.getClientId();
        // Lock the client NPA row before touching mapping rows: reEvaluateClient's exit path holds this lock while
        // deleting the same mappings, so mutating mappings first would take the two locks in opposite orders and
        // deadlock a user close/foreclosure against the nightly reconcile. If no status row exists no mapping can
        // exist either (the row is created on first NPA entry and never deleted), so skipping the lock is safe then.
        clientNpaRepository.findByClientIdForUpdate(clientId);
        clientNpaLoanRepository.findByClientIdAndLoanId(clientId, loan.getId()).ifPresent(mapping -> {
            archiveLoanHistory(mapping, DateUtils.getBusinessLocalDate());
            clientNpaLoanRepository.deleteByClientIdAndLoanId(clientId, loan.getId());
        });
        if (isClientNpa(clientId) && configurationDomainService.isClientNpaEnabled()) {
            reEvaluateClient(clientId);
        }
    }

    /**
     * Isolated re-evaluation used by the nightly UPDATE_NPA reconcile sweep: each client runs in its own transaction so
     * a failure on one client neither poisons the shared tasklet transaction nor aborts the remaining clients.
     * <p>
     * COB never calls this (or {@link #reEvaluateClient}): the UPDATE_NPA COB business step always applies the
     * loan-level rule only, irrespective of {@code enable-client-npa}. Client entry/exit/contagion stays with the
     * nightly job plus {@link #onLoanClosed} (which may call {@link #reEvaluateClient}) and {@link #onLoanDisbursed}
     * (contagion only).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reEvaluateClientInNewTransaction(final Long clientId) {
        reEvaluateClient(clientId);
    }

    public void reEvaluateClient(final Long clientId) {
        // Serialize concurrent job/API evaluations for the same client
        final ClientNpa locked = lockOrCreateClientNpa(clientId);

        final LocalDate today = DateUtils.getBusinessLocalDate();
        final List<Loan> activeLoans = findActiveClientLoans(clientId);
        final List<Loan> independentlyNpaLoans = activeLoans.stream().filter(l -> loanNpaUpdateService.wouldBeLoanLevelNpa(l, today))
                .toList();
        final boolean currentlyNpa = locked.isNpa();
        final LocalDate mappingStart = locked.getNpaStartDate() != null ? locked.getNpaStartDate() : today;

        if (!independentlyNpaLoans.isEmpty()) {
            final Loan triggerLoan = selectTriggerLoan(independentlyNpaLoans);
            if (!currentlyNpa) {
                enterClientNpa(locked, triggerLoan, activeLoans, ClientNpaMovementType.SYSTEM);
            } else {
                if (!Objects.equals(locked.getTriggerLoanId(), triggerLoan.getId())) {
                    locked.setTriggerLoanId(triggerLoan.getId());
                    clientNpaRepository.save(locked);
                    final Client client = clientRepositoryWrapper.findOneWithNotFoundDetection(clientId);
                    businessEventNotifierService.notifyPostBusinessEvent(new ClientTriggerLoanChangedBusinessEvent(client));
                }
                ensureAllActiveLoansMapped(clientId, activeLoans, mappingStart);
                syncActiveLoansToClientNpa(activeLoans);
            }
            return;
        }

        if (!currentlyNpa) {
            // Self-heal contagion residue: the reconcile stale-flag entry query selects any client with an active
            // is_npa=true loan and no NPA status row — e.g. a loan on a product without NPA config stranded by an
            // earlier exit. Without this repair the client would be re-selected every night and this method would
            // no-op forever. Loans kept NPA by the loan-level rule never reach here (they are independently NPA).
            for (final Loan loan : activeLoans) {
                if (loan.isNpa()) {
                    clearLoanNpaState(loan, today);
                }
            }
            return;
        }

        final ClientNpaExitStrategy strategy = locked.getExitStrategy() != null ? locked.getExitStrategy()
                : ClientNpaExitStrategy.ANY_NPA_LOAN_EXISTS;
        if (shouldExit(strategy, activeLoans)) {
            exitClientNpa(locked, strategy.name());
        } else {
            // The client stays NPA with no independently-NPA loan — the steady state for STAY_NPA and for
            // ALL_ARREARS_CLEARED with arrears below the NPA threshold. Contagion must be (re)applied here too:
            // the reconcile drift query targets exactly this shape (active loan of an NPA client missing its flag
            // or mapping), and without this the repair it schedules would be a no-op for those two strategies.
            ensureAllActiveLoansMapped(clientId, activeLoans, mappingStart);
            syncActiveLoansToClientNpa(activeLoans);
        }
        // When exit strategy keeps the client in NPA (STAY_NPA, or ALL_ARREARS_CLEARED with remaining arrears)
        // and no loan-level NPA loans remain, preserve the existing trigger_loan_id for audit — do not null it.
    }

    private ClientNpa lockOrCreateClientNpa(final Long clientId) {
        final Optional<ClientNpa> existing = clientNpaRepository.findByClientIdForUpdate(clientId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Insert in a separate transaction: a unique-constraint violation inside the caller's transaction would abort
        // it on PostgreSQL (and mark it rollback-only in Spring), poisoning e.g. the surrounding COB loan transaction.
        // In its own transaction the losing insert of a concurrent race rolls back cleanly and we lock the winner's
        // row. The placeholder row (is_npa=false) surviving a caller rollback is harmless.
        final TransactionTemplate insertTemplate = new TransactionTemplate(transactionManager);
        insertTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            insertTemplate.executeWithoutResult(status -> clientNpaRepository.saveAndFlush(new ClientNpa(clientId)));
        } catch (final DataIntegrityViolationException ex) {
            log.debug("Concurrent client NPA row insert for clientId={}, locking existing row", clientId);
        }
        return clientNpaRepository.findByClientIdForUpdate(clientId)
                .orElseThrow(() -> new IllegalStateException("Unable to lock client NPA row for clientId=" + clientId));
    }

    private void enterClientNpa(final ClientNpa clientNpa, final Loan triggerLoan, final List<Loan> activeLoans,
            final ClientNpaMovementType movementType) {
        final Long clientId = clientNpa.getClientId();
        final LocalDate today = DateUtils.getBusinessLocalDate();
        final ClientNpaExitStrategy exitStrategy = resolveExitStrategy();

        final LocalDate npaStartDate = deriveNpaStartDate(activeLoans, today);

        clientNpa.setNpa(true);
        clientNpa.setNpaStartDate(npaStartDate);
        clientNpa.setTriggerLoanId(triggerLoan.getId());
        clientNpa.setMovementType(movementType);
        clientNpa.setExitStrategy(exitStrategy);
        clientNpaRepository.save(clientNpa);

        // No history row here: a spell is recorded only once it ends (see archiveClientHistory). The running spell is
        // fr_client_npa_status itself, so there is never an open-ended history row to find and close.
        // Mapping start matches the client spell start so loan history aligns with fr_client_npa_history.
        ensureAllActiveLoansMapped(clientId, activeLoans, npaStartDate);
        syncActiveLoansToClientNpa(activeLoans);

        final Client client = clientRepositoryWrapper.findOneWithNotFoundDetection(clientId);
        businessEventNotifierService.notifyPostBusinessEvent(new ClientEnteredNpaBusinessEvent(client));
    }

    /**
     * The classification date is the day the arrears actually crossed the product's NPA threshold (earliest across the
     * client's qualifying loans), not the day the batch noticed: on catch-up after missed runs this keeps
     * npa_start_date true for reporting and stops the pre-NPA-start guard from rejecting payments legitimately dated
     * inside the gap. Money movements (suspense conversion) still post on the evaluation date. Falls back to the
     * business date when arrears data cannot produce a past crossing day.
     */
    private LocalDate deriveNpaStartDate(final List<Loan> activeLoans, final LocalDate today) {
        LocalDate earliest = today;
        for (final Loan loan : activeLoans) {
            final Integer npaDays = loan.loanProduct() != null ? loan.loanProduct().getOverdueDaysForNPA() : null;
            if (npaDays == null) {
                continue;
            }
            final LocalDate overdueSince = getOverdueSinceDate(loan.getId());
            if (overdueSince == null) {
                continue;
            }
            final LocalDate crossing = overdueSince.plusDays(npaDays);
            if (crossing.isBefore(earliest)) {
                earliest = crossing;
            }
        }
        return earliest;
    }

    private void exitClientNpa(final ClientNpa clientNpa, final String exitReason) {
        final Long clientId = clientNpa.getClientId();
        final LocalDate today = DateUtils.getBusinessLocalDate();
        final List<Loan> activeLoans = findActiveClientLoans(clientId);

        // Capture the spell before the status row is cleared — it is the only record of how this spell started
        final LocalDate spellStartDate = clientNpa.getNpaStartDate() != null ? clientNpa.getNpaStartDate() : today;
        final Long spellTriggerLoanId = clientNpa.getTriggerLoanId();
        final ClientNpaMovementType spellMovementType = clientNpa.getMovementType();

        clientNpa.setNpa(false);
        clientNpa.setNpaStartDate(null);
        clientNpa.setTriggerLoanId(null);
        clientNpaRepository.save(clientNpa);

        // Recalculate each loan from arrears rules now that client NPA is cleared
        for (final Loan loan : activeLoans) {
            clearLoanNpaState(loan, today);
        }

        clientNpaHistoryRepository
                .save(new ClientNpaHistory(clientId, spellStartDate, today, spellTriggerLoanId, spellMovementType, exitReason));

        for (final ClientNpaLoan mapping : clientNpaLoanRepository.findByClientId(clientId)) {
            archiveLoanHistory(mapping, today);
        }
        clientNpaLoanRepository.deleteByClientId(clientId);

        final Client client = clientRepositoryWrapper.findOneWithNotFoundDetection(clientId);
        businessEventNotifierService.notifyPostBusinessEvent(new ClientExitedNpaBusinessEvent(client));
    }

    /**
     * Re-derives a loan's NPA state from the loan-level rule. For loans the rule cannot evaluate (product without NPA
     * config), clears the contagion flag and reverses suspense explicitly — {@code updateNpaStatusForLoan}
     * early-returns for them, which would otherwise strand {@code is_npa=true} and the accrual suspense forever after
     * client exit, since contagion flags every active loan regardless of product config.
     */
    private void clearLoanNpaState(final Loan loan, final LocalDate businessDate) {
        if (loan.loanProduct() != null && loan.loanProduct().getOverdueDaysForNPA() != null) {
            loanNpaUpdateService.updateNpaStatusForLoan(loan, businessDate);
        } else if (loan.isNpa()) {
            if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
                // Clears is_npa and reverses the outstanding suspense balance, joining this transaction
                loanAccrualsProcessingService.reverseAccrualSuspenseForAlreadyNonNpaLoans(List.of(loan.getId()));
            } else {
                loan.setNpa(false);
                loanRepositoryWrapper.save(loan);
            }
        }
    }

    private void ensureAllActiveLoansMapped(final Long clientId, final List<Loan> activeLoans, final LocalDate startDate) {
        for (final Loan loan : activeLoans) {
            mapLoanUnderClientNpa(clientId, loan, startDate);
        }
    }

    private void mapLoanUnderClientNpa(final Long clientId, final Loan loan, final LocalDate startDate) {
        if (clientNpaLoanRepository.findByClientIdAndLoanId(clientId, loan.getId()).isPresent()) {
            return;
        }
        // The client spell start can be backdated (derived crossing day) or predate a later-disbursed sibling; a loan
        // cannot have been under NPA before it existed, so clamp the mapping start to its disbursement date.
        final LocalDate disbursementDate = loan.getDisbursementDate();
        final LocalDate effectiveStart = disbursementDate != null && disbursementDate.isAfter(startDate) ? disbursementDate : startDate;
        final Integer accountingType = loan.loanProduct() != null && loan.loanProduct().getAccountingRule() != null
                ? loan.loanProduct().getAccountingRule().getValue()
                : null;
        clientNpaLoanRepository.save(new ClientNpaLoan(clientId, loan.getId(), effectiveStart, accountingType));
    }

    /**
     * Turns a live mapping row into a completed history row. The mapping carries the start date, so the spell is
     * recorded in full at the moment the loan leaves client NPA; the caller then deletes the mapping.
     */
    private void archiveLoanHistory(final ClientNpaLoan mapping, final LocalDate endDate) {
        clientNpaLoanHistoryRepository.save(new ClientNpaLoanHistory(mapping.getClientId(), mapping.getLoanId(), mapping.getStartDate(),
                endDate, mapping.getAccountingType()));
    }

    /**
     * Sets {@code loan.is_npa = true} first, then converts accruals to suspense in the same transaction so client NPA
     * state and suspense cannot diverge.
     */
    private void syncActiveLoansToClientNpa(final List<Loan> loans) {
        for (final Loan loan : loans) {
            if (!loan.isNpa()) {
                loan.setNpa(true);
                loanRepositoryWrapper.save(loan);
            }
        }
        final List<Long> periodicLoanIds = loans.stream().filter(Loan::isPeriodicAccrualAccountingEnabledOnLoanProduct).map(Loan::getId)
                .toList();
        if (!periodicLoanIds.isEmpty()) {
            loanAccrualsProcessingService.convertAccrualToSuspenseForAlreadyNpaLoans(periodicLoanIds);
        }
    }

    private boolean shouldExit(final ClientNpaExitStrategy strategy, final List<Loan> activeLoans) {
        final LocalDate today = DateUtils.getBusinessLocalDate();
        return switch (strategy) {
            case STAY_NPA -> false;
            // Exit when no active loan is independently NPA (arrears may still exist below the NPA threshold)
            case ANY_NPA_LOAN_EXISTS -> activeLoans.stream().noneMatch(l -> loanNpaUpdateService.wouldBeLoanLevelNpa(l, today));
            // Stricter: exit only when no active loan has any arrears aging row
            case ALL_ARREARS_CLEARED -> activeLoans.stream().noneMatch(l -> getOverdueSinceDate(l.getId()) != null);
        };
    }

    private LocalDate getOverdueSinceDate(final Long loanId) {
        final List<LocalDate> dates = jdbcTemplate.query("SELECT overdue_since_date_derived FROM m_loan_arrears_aging WHERE loan_id = ?",
                (rs, rowNum) -> rs.getDate(1) != null ? rs.getDate(1).toLocalDate() : null, loanId);
        return dates.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Loan selectTriggerLoan(final List<Loan> npaLoans) {
        if (npaLoans == null || npaLoans.isEmpty()) {
            return null;
        }
        return npaLoans.stream().min(Comparator.comparing(Loan::getId)).orElse(null);
    }

    private ClientNpaExitStrategy resolveExitStrategy() {
        final String strategy = configurationDomainService.retrieveClientNpaExitStrategy();
        try {
            return ClientNpaExitStrategy.valueOf(strategy);
        } catch (final IllegalArgumentException ex) {
            log.warn("Unknown client-npa-exit-strategy '{}', defaulting to ANY_NPA_LOAN_EXISTS", strategy);
            return ClientNpaExitStrategy.ANY_NPA_LOAN_EXISTS;
        }
    }

    private List<Loan> findActiveClientLoans(final Long clientId) {
        return loanRepository.findLoanByClientId(clientId).stream().filter(l -> LoanStatus.ACTIVE.equals(l.getStatus())).toList();
    }
}
