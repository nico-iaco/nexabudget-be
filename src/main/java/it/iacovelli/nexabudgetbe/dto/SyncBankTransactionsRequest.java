package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SyncBankTransactionsRequest {

    private BigDecimal actualBalance;

}
