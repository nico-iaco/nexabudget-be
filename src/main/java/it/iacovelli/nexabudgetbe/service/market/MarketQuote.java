package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.PriceSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Quotazione restituita da un provider. È una classe non final (non un record) perché finisce nella cache Redis,
 * dove la serializzazione con default typing {@code NON_FINAL} non conserverebbe il tipo dei record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketQuote {
    private String symbol;
    private BigDecimal price;
    // Valuta della quotazione, già normalizzata (GBp → GBP, ecc.)
    private String currency;
    private LocalDateTime asOf;
    private PriceSource source;
}
