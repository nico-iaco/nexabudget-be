package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
import it.iacovelli.nexabudgetbe.dto.GocardlessTransactions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GocardlessServiceBookedTransactionsTest {

    @Test
    void extractBookedTransactions_ignoresPending() {
        GocardlessTransactions transactions = new GocardlessTransactions();
        GocardlessTransaction booked = transaction("booked-1");
        GocardlessTransaction pending = transaction("pending-1");
        transactions.setBooked(List.of(booked));
        transactions.setPending(List.of(pending));
        transactions.setAll(List.of(booked, pending));

        assertEquals(List.of(booked), GocardlessService.extractBookedTransactions(transactions, "acc-1"));
    }

    @Test
    void extractBookedTransactions_fallsBackToAllWhenBookedMissing() {
        GocardlessTransactions transactions = new GocardlessTransactions();
        GocardlessTransaction tx = transaction("tx-1");
        transactions.setAll(List.of(tx));

        assertEquals(List.of(tx), GocardlessService.extractBookedTransactions(transactions, "acc-1"));
    }

    @Test
    void extractBookedTransactions_nullTransactions_returnsEmptyList() {
        assertTrue(GocardlessService.extractBookedTransactions(null, "acc-1").isEmpty());
    }

    private GocardlessTransaction transaction(String id) {
        GocardlessTransaction t = new GocardlessTransaction();
        t.setTransactionId(id);
        return t;
    }
}
