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
    /** Banca su cui è stato dato il consenso: unica fonte del nome istituto, i singoli conti non lo riportano. */
    private EnableBankingAspspRef aspsp;
}
