package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessGetTransactionsRequest {
    private String requisitionId;
    private String accountId;
}
