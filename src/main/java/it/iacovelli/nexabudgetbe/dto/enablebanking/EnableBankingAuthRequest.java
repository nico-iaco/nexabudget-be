package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EnableBankingAuthRequest {
    private EnableBankingAccess access;
    private EnableBankingAspspRef aspsp;
    private String state;
    @JsonProperty("redirect_url")
    private String redirectUrl;
    @JsonProperty("psu_type")
    private String psuType;
}
