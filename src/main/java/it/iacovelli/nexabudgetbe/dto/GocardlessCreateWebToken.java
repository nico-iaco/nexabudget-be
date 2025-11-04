package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessCreateWebToken {
    private String link;
    private String requisitionId;
}
