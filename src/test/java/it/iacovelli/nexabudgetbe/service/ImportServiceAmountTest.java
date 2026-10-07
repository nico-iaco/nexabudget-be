package it.iacovelli.nexabudgetbe.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportServiceAmountTest {

    private static BigDecimal parse(String raw) {
        return new BigDecimal(ImportService.normalizeAmount(raw));
    }

    @Test
    void normalizeAmount_italianFormatWithThousands() {
        assertEquals(new BigDecimal("1234.56"), parse("1.234,56"));
        assertEquals(new BigDecimal("-1234567.89"), parse("-1.234.567,89 €"));
    }

    @Test
    void normalizeAmount_usFormatWithThousands() {
        assertEquals(new BigDecimal("1234.56"), parse("1,234.56"));
        assertEquals(new BigDecimal("-1234.56"), parse("$-1,234.56"));
    }

    @Test
    void normalizeAmount_singleSeparator() {
        assertEquals(new BigDecimal("12.50"), parse("12,50"));
        assertEquals(new BigDecimal("12.50"), parse("12.50"));
        assertEquals(new BigDecimal("-7"), parse("-7"));
    }
}
