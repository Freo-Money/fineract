package org.apache.fineract.portfolio.loanaccount.data;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class LoanDueDetailsDTO {
    private BigDecimal principalDue;
    private BigDecimal interestDue;
    private BigDecimal feeChargesDue;
    private BigDecimal penaltyChargesDue;
    private List<LoanChargeData> feeChargesDueDetails;
    private List<LoanChargeData> penaltyChargesDueDetails;
}
