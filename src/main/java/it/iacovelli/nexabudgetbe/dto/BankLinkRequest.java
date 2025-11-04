package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class BankLinkRequest {

    private String institutionId;
    private Long localAccountId;

}
