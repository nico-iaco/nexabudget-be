package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.util.List;

@Data
public class GocardlessTransaction {
    private String transactionId;
    private String bookingDate;
    private String valueDate;
    private String bookingDateTime;
    private String valueDateTime;
    private GocardlessAmount transactionAmount;
    private String creditorName;
    private String debtorName;
    private List<String> remittanceInformationUnstructuredArray;
    private String proprietaryBankTransactionCode;
    private String internalTransactionId;
    private String payeeName;
    private String date;
}
