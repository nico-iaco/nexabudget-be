package it.iacovelli.nexabudgetbe.model;

import java.math.BigDecimal;

public enum InvestmentAssetType {
    ETF,
    STOCK,
    BOND,
    FUND,
    OTHER;

    private static final BigDecimal PERCENT = new BigDecimal("0.01");

    /**
     * Moltiplicatore da applicare a quantità × prezzo per ottenere il valore.
     * Le obbligazioni sono quotate in % del nominale (quantity = nominale, price = % → valore = nominale × price / 100).
     */
    public BigDecimal priceMultiplier() {
        return this == BOND ? PERCENT : BigDecimal.ONE;
    }
}
