package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.util.List;

@Data
public class GocardlessTransactions {
    private List<GocardlessTransaction> booked;
    private List<GocardlessTransaction> pending;
    private List<GocardlessTransaction> all;
}
