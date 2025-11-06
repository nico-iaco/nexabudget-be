package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class GocardlessCreateWebTokenRequest {
    private String institutionId;
    private UUID localAccountId;
}
