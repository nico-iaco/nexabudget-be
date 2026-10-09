package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import it.iacovelli.nexabudgetbe.model.CryptoHolding;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.model.UserBinanceKeys;
import it.iacovelli.nexabudgetbe.model.UserCoinbaseKeys;
import it.iacovelli.nexabudgetbe.repository.CryptoHoldingRepository;
import it.iacovelli.nexabudgetbe.repository.UserBinanceKeysRepository;
import it.iacovelli.nexabudgetbe.repository.UserCoinbaseKeysRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sync Binance/Coinbase: recupero fuori transazione, delete+insert atomici, sync concorrenti serializzate,
 * precondizioni verificate in modo sincrono.
 */
class CryptoPortfolioServiceSyncTest {

    private CryptoHoldingRepository holdingRepository;
    private UserBinanceKeysRepository binanceKeysRepository;
    private UserCoinbaseKeysRepository coinbaseKeysRepository;
    private BinanceService binanceService;
    private CoinbaseService coinbaseService;
    private PlatformTransactionManager transactionManager;
    private CryptoPortfolioService service;
    private User user;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(CryptoHoldingRepository.class);
        binanceKeysRepository = mock(UserBinanceKeysRepository.class);
        coinbaseKeysRepository = mock(UserCoinbaseKeysRepository.class);
        binanceService = mock(BinanceService.class);
        coinbaseService = mock(CoinbaseService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new CryptoPortfolioService(holdingRepository, binanceKeysRepository, coinbaseKeysRepository,
                binanceService, coinbaseService, mock(CurrencyConversionService.class), transactionManager);

        user = new User();
        user.setId(UUID.randomUUID());

        UserBinanceKeys binanceKeys = new UserBinanceKeys();
        binanceKeys.setApiKey("key");
        binanceKeys.setApiSecret("secret");
        when(binanceKeysRepository.findByUser(user)).thenReturn(Optional.of(binanceKeys));

        UserCoinbaseKeys coinbaseKeys = new UserCoinbaseKeys();
        coinbaseKeys.setApiKeyName("name");
        coinbaseKeys.setPrivateKey("pk");
        when(coinbaseKeysRepository.findByUser(user)).thenReturn(Optional.of(coinbaseKeys));
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncFetchesOutsideTransactionThenReplacesAtomically() {
        when(binanceService.getAllWalletsIncludingEarn("key", "secret")).thenReturn(List.of(
                new CryptoBalance("btc", new BigDecimal("0.5")),
                new CryptoBalance("BTC", new BigDecimal("0.25")),
                new CryptoBalance("ETH", new BigDecimal("2"))));

        service.syncBinanceHoldings(user);

        InOrder inOrder = inOrder(binanceService, transactionManager, holdingRepository);
        inOrder.verify(binanceService).getAllWalletsIncludingEarn("key", "secret");
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(holdingRepository).bulkDeleteByUserAndSource(user, HoldingSource.BINANCE);
        ArgumentCaptor<List<CryptoHolding>> saved = ArgumentCaptor.forClass(List.class);
        inOrder.verify(holdingRepository).saveAll(saved.capture());
        inOrder.verify(transactionManager).commit(any());

        // "btc" e "BTC" accorpati in un solo holding
        assertEquals(2, saved.getValue().size());
        CryptoHolding btc = saved.getValue().stream().filter(h -> "BTC".equals(h.getSymbol())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.75").compareTo(btc.getAmount()));
        assertEquals(HoldingSource.BINANCE, btc.getSource());
    }

    @Test
    void fetchFailureDeletesNothing() {
        when(binanceService.getAllWalletsIncludingEarn(anyString(), anyString()))
                .thenThrow(new RuntimeException("Simple Earn non disponibile"));

        assertThrows(RuntimeException.class, () -> service.syncBinanceHoldings(user));

        verify(transactionManager, never()).getTransaction(any());
        verify(holdingRepository, never()).bulkDeleteByUserAndSource(any(), any());
        verify(holdingRepository, never()).saveAll(any());
    }

    @Test
    void insertFailureRollsBackAndReleasesLock() {
        when(coinbaseService.getWallets("name", "pk"))
                .thenReturn(List.of(new CryptoBalance("BTC", BigDecimal.ONE)));
        when(holdingRepository.saveAll(any())).thenThrow(new RuntimeException("DB down"));

        assertThrows(RuntimeException.class, () -> service.syncCoinbaseHoldings(user));

        verify(holdingRepository).bulkDeleteByUserAndSource(user, HoldingSource.COINBASE);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());

        // Il lock è stato rilasciato: una nuova sync riparte
        assertThrows(RuntimeException.class, () -> service.syncCoinbaseHoldings(user));
        verify(coinbaseService, times(2)).getWallets("name", "pk");
    }

    @Test
    void concurrentSyncForSameUserAndSourceIsSkipped() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(binanceService.getAllWalletsIncludingEarn(anyString(), anyString())).thenAnswer(inv -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(5, TimeUnit.SECONDS));
            return List.of(new CryptoBalance("BTC", BigDecimal.ONE));
        });
        when(coinbaseService.getWallets(anyString(), anyString()))
                .thenReturn(List.of(new CryptoBalance("ETH", BigDecimal.ONE)));

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> service.syncBinanceHoldings(user));
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS));

        // Secondo click mentre la prima sync Binance è in corso: ignorato
        service.syncBinanceHoldings(user);
        // Sorgente diversa: non bloccata
        service.syncCoinbaseHoldings(user);

        releaseFetch.countDown();
        first.get(5, TimeUnit.SECONDS);

        verify(binanceService, times(1)).getAllWalletsIncludingEarn(anyString(), anyString());
        verify(holdingRepository, times(1)).bulkDeleteByUserAndSource(user, HoldingSource.BINANCE);
        verify(holdingRepository, times(1)).bulkDeleteByUserAndSource(user, HoldingSource.COINBASE);

        // Terminata la prima, una nuova sync Binance riparte
        service.syncBinanceHoldings(user);
        verify(binanceService, times(2)).getAllWalletsIncludingEarn(anyString(), anyString());
    }

    @Test
    void missingKeysAreReportedSynchronously() {
        User other = new User();
        other.setId(UUID.randomUUID());
        when(binanceKeysRepository.findByUser(other)).thenReturn(Optional.empty());
        when(coinbaseKeysRepository.findByUser(other)).thenReturn(Optional.empty());

        ResponseStatusException binance = assertThrows(ResponseStatusException.class,
                () -> service.requireBinanceKeys(other));
        assertEquals(HttpStatus.NOT_FOUND, binance.getStatusCode());
        ResponseStatusException coinbase = assertThrows(ResponseStatusException.class,
                () -> service.requireCoinbaseKeys(other));
        assertEquals(HttpStatus.NOT_FOUND, coinbase.getStatusCode());

        assertDoesNotThrow(() -> service.requireBinanceKeys(user));
        assertDoesNotThrow(() -> service.requireCoinbaseKeys(user));
        verify(binanceService, never()).getAllWalletsIncludingEarn(anyString(), anyString());
        verify(holdingRepository, never()).bulkDeleteByUserAndSource(eq(other), any());
    }
}
