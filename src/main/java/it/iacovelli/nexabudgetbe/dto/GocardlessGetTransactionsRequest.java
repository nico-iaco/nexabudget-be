package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class GocardlessGetTransactionsRequest {
    private String requisitionId;
    private String accountId;
    private LocalDate startDate;
    private LocalDate endDate;
}
