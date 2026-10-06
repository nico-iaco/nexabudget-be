package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.GocardlessAmount;
import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.service.GocardlessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GocardlessAggregationProviderTest {

    @Mock
    private GocardlessService gocardlessService;

    @Test
    void fetchTransactions_prefersTransactionId_thenInternalTransactionId() {
        GocardlessTransaction withId = transaction("tx-1", "internal-1");
        GocardlessTransaction withoutId = transaction(null, "internal-2");
        GocardlessTransaction withoutAnyId = transaction(null, null);
        when(gocardlessService.getGoCardlessTransaction("req-1", "acc-1")).thenReturn(List.of(withId, withoutId, withoutAnyId));

        Account account = new Account();
        account.setRequisitionId("req-1");
        account.setExternalAccountId("acc-1");

        List<NormalizedBankTransaction> result = new GocardlessAggregationProvider(gocardlessService).fetchTransactions(account, null);

        assertEquals("tx-1", result.get(0).getExternalId());
        assertEquals("internal-2", result.get(1).getExternalId());
        // Nessun id: lasciato null qui, valorizzato da BankTransactionIds in fase di import.
        assertNull(result.get(2).getExternalId());
    }

    private GocardlessTransaction transaction(String transactionId, String internalTransactionId) {
        GocardlessTransaction t = new GocardlessTransaction();
        t.setTransactionId(transactionId);
        t.setInternalTransactionId(internalTransactionId);
        t.setBookingDate("2026-03-01");
        GocardlessAmount amount = new GocardlessAmount();
        amount.setAmount("-12.50");
        amount.setCurrency("EUR");
        t.setTransactionAmount(amount);
        return t;
    }
}
