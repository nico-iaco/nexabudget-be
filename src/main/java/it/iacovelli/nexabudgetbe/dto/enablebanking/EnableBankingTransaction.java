package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingTransaction {
    @JsonProperty("entry_reference")
    private String entryReference;
    @JsonProperty("booking_date")
    private String bookingDate;
    @JsonProperty("value_date")
    private String valueDate;
    @JsonProperty("transaction_amount")
    private EnableBankingAmount transactionAmount;
    /** "CRDT" (entrata) o "DBIT" (uscita). */
    @JsonProperty("credit_debit_indicator")
    private String creditDebitIndicator;
    @JsonProperty("remittance_information")
    private List<String> remittanceInformation;
    private EnableBankingParty creditor;
    private EnableBankingParty debtor;
}
