package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingAuthResponse {
    private String url;
}
