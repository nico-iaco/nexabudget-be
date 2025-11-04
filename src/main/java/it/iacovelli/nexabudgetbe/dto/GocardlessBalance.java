package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessBalance {
    private GocardlessAmount balanceAmount;
    private String balanceType;
    private String referenceDate;
}
