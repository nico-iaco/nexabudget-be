package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class BankLinkRequest {

    private String institutionId;
    private UUID localAccountId;

}
