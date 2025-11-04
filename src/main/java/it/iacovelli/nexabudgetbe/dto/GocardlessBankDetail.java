package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessBankDetail {
    private String account_id;
    private GocardlessBank institution;
    private String name;
}
