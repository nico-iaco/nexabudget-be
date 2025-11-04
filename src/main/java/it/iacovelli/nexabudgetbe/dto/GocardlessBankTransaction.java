package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.util.List;

@Data
public class GocardlessBankTransaction {
    private List<GocardlessBalance> balances;
    private String institutionId;
    private int startingBalance;
    private GocardlessTransactions transactions;
}
