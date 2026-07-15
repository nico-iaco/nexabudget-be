package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingSessionResponse {
    @JsonProperty("session_id")
    private String sessionId;
    private List<EnableBankingAccount> accounts;
}
