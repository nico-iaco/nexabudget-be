package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Transazione bancaria normalizzata, indipendente dal provider di origine.
 * {@code amount} porta il segno originale (positivo = entrata, negativo = uscita) — la conversione
 * a {@link it.iacovelli.nexabudgetbe.model.TransactionType} avviene in fase di import.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedBankTransaction {
    /** Id univoco lato provider, usato per la dedup su re-sync. */
    private String externalId;
    private BigDecimal amount;
    private String currency;
    /** Data valuta se disponibile, altrimenti data contabile — già risolta dal provider adapter. */
    private String date;
    private String creditorName;
    private String debtorName;
    private String remittanceInformation;
    private String payeeName;
}
