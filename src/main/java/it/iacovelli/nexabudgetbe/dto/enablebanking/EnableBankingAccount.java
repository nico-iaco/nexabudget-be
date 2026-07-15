package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingAccount {
    /** Identificativo del conto lato Enable Banking, usato per tutte le chiamate successive. */
    private String uid;
    private String name;
    private String currency;
    @com.fasterxml.jackson.annotation.JsonProperty("account_id")
    private EnableBankingAccountId accountId;
}
