package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BankTransactionIdsTest {

    private final UUID accountId = UUID.randomUUID();

    @Test
    void ensureExternalIds_keepsExistingIds() {
        NormalizedBankTransaction t = tx("bank-id", "10.00", "Bar");

        BankTransactionIds.ensureExternalIds(List.of(t), accountId);

        assertEquals("bank-id", t.getExternalId());
    }

    @Test
    void ensureExternalIds_isDeterministicAcrossSyncs() {
        NormalizedBankTransaction first = tx(null, "10.00", "Bar");
        NormalizedBankTransaction second = tx(null, "10.0", "Bar");

        BankTransactionIds.ensureExternalIds(List.of(first), accountId);
        BankTransactionIds.ensureExternalIds(List.of(second), accountId);

        assertTrue(first.getExternalId().startsWith(BankTransactionIds.HASH_PREFIX));
        assertEquals(first.getExternalId(), second.getExternalId(), "la scala dell'importo non deve cambiare l'id");
    }

    @Test
    void ensureExternalIds_distinguishesIdenticalTransactionsInSameBatch() {
        NormalizedBankTransaction a = tx(null, "1.50", "Caffè");
        NormalizedBankTransaction b = tx(null, "1.50", "Caffè");

        BankTransactionIds.ensureExternalIds(List.of(a, b), accountId);

        assertNotEquals(a.getExternalId(), b.getExternalId());
    }

    @Test
    void ensureExternalIds_isScopedToAccount() {
        NormalizedBankTransaction a = tx(null, "10.00", "Bar");
        NormalizedBankTransaction b = tx(null, "10.00", "Bar");

        BankTransactionIds.ensureExternalIds(List.of(a), accountId);
        BankTransactionIds.ensureExternalIds(List.of(b), UUID.randomUUID());

        assertNotEquals(a.getExternalId(), b.getExternalId());
    }

    private NormalizedBankTransaction tx(String externalId, String amount, String remittance) {
        return NormalizedBankTransaction.builder()
                .externalId(externalId)
                .amount(new BigDecimal(amount).negate())
                .date("2026-03-01")
                .remittanceInformation(remittance)
                .build();
    }
}
