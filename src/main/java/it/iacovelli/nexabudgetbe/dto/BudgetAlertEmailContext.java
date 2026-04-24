package it.iacovelli.nexabudgetbe.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class BudgetAlertEmailContext {
    private String userEmail;
    private String username;
    private String categoryName;
    private BigDecimal budgetLimit;
    private String currency;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer thresholdPercentage;
    private BigDecimal usagePercent;
}
