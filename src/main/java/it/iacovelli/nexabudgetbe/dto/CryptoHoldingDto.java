package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.HoldingSource;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CryptoHoldingDto {

    private UUID id;
    private String symbol;
    private BigDecimal amount;
    private HoldingSource source;

}
