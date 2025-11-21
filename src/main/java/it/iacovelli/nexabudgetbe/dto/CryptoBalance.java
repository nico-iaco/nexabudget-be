package it.iacovelli.nexabudgetbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoBalance {
    private String symbol;
    private BigDecimal amount;
}
