package it.iacovelli.nexabudgetbe.dto.enablebanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EnableBankingAspspsResponse {
    private List<EnableBankingAspsp> aspsps;
}
