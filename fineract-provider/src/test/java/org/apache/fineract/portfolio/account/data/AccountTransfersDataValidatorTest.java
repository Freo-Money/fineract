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
package org.apache.fineract.portfolio.account.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the future-date rule on {@code refundByTransfer} against the <b>real</b> validator.
 * <p>
 * Deliberately not mocked. The rule's whole purpose is to fire before the paid-in-advance cap in the service, and a
 * test that stubs this validator would keep passing even if the rule were removed - the guard would silently become
 * dead code while the suite stayed green.
 */
class AccountTransfersDataValidatorTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();
    private final AccountTransfersDataValidator validator = new AccountTransfersDataValidator(fromJsonHelper,
            new AccountTransfersDetailDataValidator(fromJsonHelper));

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void refundByTransferRejectsAFutureTransferDate() {
        final JsonCommand command = transferCommand(LocalDate.now(ZoneId.systemDefault()).plusDays(1));

        assertThatThrownBy(() -> validator.validateRefundByTransfer(command)).isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(thrown -> {
                    final var errors = ((PlatformApiDataValidationException) thrown).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getUserMessageGlobalisationCode()).endsWith("cannot.be.a.future.date");
                    assertThat(errors.get(0).getParameterName()).isEqualTo("transferDate");
                });
    }

    @Test
    void refundByTransferAcceptsTodayAndEarlier() {
        assertThatCode(() -> validator.validateRefundByTransfer(transferCommand(LocalDate.now(ZoneId.systemDefault()))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateRefundByTransfer(transferCommand(LocalDate.now(ZoneId.systemDefault()).minusDays(1))))
                .doesNotThrowAnyException();
    }

    /**
     * The rule is deliberately scoped to the refund path. {@code validate} is shared with ordinary account transfers,
     * so adding it there would forbid a future date on every savings and loan transfer.
     */
    @Test
    void ordinaryTransferValidationStillAllowsAFutureDate() {
        final JsonCommand command = transferCommand(LocalDate.now(ZoneId.systemDefault()).plusDays(1));

        assertThatCode(() -> validator.validate(command)).doesNotThrowAnyException();
    }

    private JsonCommand transferCommand(final LocalDate transferDate) {
        final String json = """
                {"fromOfficeId":1,"fromClientId":1,"fromAccountType":1,"fromAccountId":1,\
                "toOfficeId":1,"toClientId":1,"toAccountType":2,"toAccountId":2,\
                "transferDate":"%s","transferAmount":100,"transferDescription":"test",\
                "locale":"en","dateFormat":"dd MMMM yyyy"}""".formatted(FORMAT.format(transferDate));
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
