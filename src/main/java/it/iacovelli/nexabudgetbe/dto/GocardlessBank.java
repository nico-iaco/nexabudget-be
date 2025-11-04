package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GocardlessBank {
    private String id;
    private String name;
    private String bic;
    @JsonProperty("transaction_total_days")
    private int transactionTotalDays;
    private List<String> countries;
    private String logo;
    @JsonProperty("max_access_valid_for_days")
    private int maxAccessValidForDays;
}
