package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingTransactionsResponse {
    private List<EnableBankingTransaction> transactions;
    @JsonProperty("continuation_key")
    private String continuationKey;
}
