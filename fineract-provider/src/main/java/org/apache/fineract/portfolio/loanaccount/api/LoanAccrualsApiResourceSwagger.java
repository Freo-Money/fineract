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
package org.apache.fineract.portfolio.loanaccount.api;

import io.swagger.v3.oas.annotations.media.Schema;

final class LoanAccrualsApiResourceSwagger {

    private LoanAccrualsApiResourceSwagger() {}

    @Schema(description = "PostLoansLoanIdAccrualsPostTillDateRequest")
    public static final class PostLoansLoanIdAccrualsPostTillDateRequest {

        private PostLoansLoanIdAccrualsPostTillDateRequest() {}

        @Schema(example = "en")
        public String locale;
        @Schema(example = "dd MMMM yyyy")
        public String dateFormat;
        @Schema(example = "04 June 2014", description = "Accruals will be posted up to and including this date. Must not be in the future.")
        public String tillDate;
    }

    @Schema(description = "PostLoansLoanIdAccrualsPostTillDateResponse")
    public static final class PostLoansLoanIdAccrualsPostTillDateResponse {

        private PostLoansLoanIdAccrualsPostTillDateResponse() {}

        @Schema(example = "1")
        public Long resourceId;
    }
}
