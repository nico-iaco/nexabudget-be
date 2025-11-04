package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GocardlessGetAccounts {
    private String id;
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    private String redirect;
    private String status;
    @JsonProperty("institution_id")
    private String institutionId;
    private String agreement;
    private String reference;
    private List<GocardlessBankDetail> accounts;
    @JsonProperty("user_language")
    private String userLanguage;
    private String link;
}
