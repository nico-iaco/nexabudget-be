package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessCreateWebTokenRequest {
    private String institutionId;
    private Long localAccountId;
}
