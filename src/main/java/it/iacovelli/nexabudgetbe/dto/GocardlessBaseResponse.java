package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;

@Data
public class GocardlessBaseResponse<T> {
    private String status;
    private T data;
}
