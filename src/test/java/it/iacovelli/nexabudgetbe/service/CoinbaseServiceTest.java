package it.iacovelli.nexabudgetbe.service;

import com.coinbase.advanced.model.accounts.Account;
import com.coinbase.advanced.model.accounts.ListAccountsResponse;
import com.coinbase.advanced.model.common.Amount;
import com.coinbase.advanced.model.portfolios.SpotPosition;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggregazione Coinbase: una sola fonte per asset (breakdown primari, account scan come fallback),
 * esclusione dei fiat, paginazione degli account e gestione dei fallimenti parziali.
 */
class CoinbaseServiceTest {

    private final CoinbaseService service = new CoinbaseService();

    private static Account account(String uuid, String currency, String available, String retailPortfolioId) {
        return new Account.Builder()
                .uuid(uuid)
                .currency(currency)
                .type("ACCOUNT_TYPE_CRYPTO")
                .availableBalance(new Amount(available, currency))
                .hold(new Amount("0", currency))
                .retailPortfolioId(retailPortfolioId)
                .build();
    }

    private static CoinbaseService.BreakdownResult ok(String portfolioId, Map<String, BigDecimal> balances, String... accountUuids) {
        return new CoinbaseService.BreakdownResult(portfolioId, true, balances, Set.of(accountUuids));
    }

    private static Map<String, BigDecimal> toMap(List<CryptoBalance> balances) {
        return balances.stream().collect(Collectors.toMap(CryptoBalance::getSymbol, CryptoBalance::getAmount));
    }

    @Test
    void sameAssetInAccountAndBreakdownIsCountedOnce() {
        List<Account> accounts = List.of(account("acc-btc", "BTC", "1", "P1"));
        List<CoinbaseService.BreakdownResult> breakdowns = List.of(ok("P1", Map.of("BTC", BigDecimal.ONE), "acc-btc"));

        Map<String, BigDecimal> result = toMap(service.mergeSources(accounts, true, breakdowns));

        assertEquals(1, result.size());
        assertEquals(0, BigDecimal.ONE.compareTo(result.get("BTC")));
    }

    @Test
    void accountOfFailedPortfolioIsUsedAsFallback() {
        List<Account> accounts = List.of(
                account("acc-btc", "BTC", "1", "P1"),
                account("acc-eth", "ETH", "3", "P2"));
        List<CoinbaseService.BreakdownResult> breakdowns = List.of(
                ok("P1", Map.of("BTC", new BigDecimal("1.5")), "acc-btc"), // include anche staking
                CoinbaseService.BreakdownResult.failed("P2"));

        Map<String, BigDecimal> result = toMap(service.mergeSources(accounts, true, breakdowns));

        assertEquals(0, new BigDecimal("1.5").compareTo(result.get("BTC")));
        assertEquals(0, new BigDecimal("3").compareTo(result.get("ETH")));
    }

    @Test
    void sameAssetInDifferentPortfoliosIsSummed() {
        List<CoinbaseService.BreakdownResult> breakdowns = List.of(
                ok("P1", Map.of("BTC", BigDecimal.ONE)),
                ok("P2", Map.of("BTC", new BigDecimal("0.5"))));

        Map<String, BigDecimal> result = toMap(service.mergeSources(List.of(), true, breakdowns));

        assertEquals(0, new BigDecimal("1.5").compareTo(result.get("BTC")));
    }

    @Test
    void noBreakdownSucceededFallsBackToAllAccountsExceptFiat() {
        Account fiat = new Account.Builder()
                .uuid("acc-eur").currency("EUR").type(CoinbaseService.FIAT_ACCOUNT_TYPE)
                .availableBalance(new Amount("100", "EUR")).retailPortfolioId("P1").build();
        List<Account> accounts = List.of(
                account("acc-btc", "BTC", "1", "P1"),
                account("acc-sol", "SOL", "4", null),
                fiat);

        Map<String, BigDecimal> result = toMap(service.mergeSources(accounts, false,
                List.of(CoinbaseService.BreakdownResult.failed("P1"))));

        assertEquals(Set.of("BTC", "SOL"), result.keySet());
    }

    @Test
    void accountScanFailedAndBreakdownFailedThrows() {
        List<CoinbaseService.BreakdownResult> breakdowns = List.of(
                ok("P1", Map.of("BTC", BigDecimal.ONE)),
                CoinbaseService.BreakdownResult.failed("P2"));

        assertThrows(IllegalStateException.class, () -> service.mergeSources(null, true, breakdowns));
    }

    @Test
    void accountScanFailedAndNoBreakdownSucceededThrows() {
        // Tutti i breakdown falliti = scansione portafogli fallita, anche se listPortfolios ha risposto
        assertThrows(IllegalStateException.class, () -> service.mergeSources(null, true,
                List.of(CoinbaseService.BreakdownResult.failed("P1"))));
        assertThrows(IllegalStateException.class, () -> service.mergeSources(null, true, List.of()));
    }

    @Test
    void accountScanFailedAndPortfolioListFailedThrows() {
        assertThrows(IllegalStateException.class, () -> service.mergeSources(null, false,
                List.of(ok("P1", Map.of("BTC", BigDecimal.ONE)))));
    }

    @Test
    void accountScanFailedButAllBreakdownsOkIsAccepted() {
        Map<String, BigDecimal> result = toMap(service.mergeSources(null, true,
                List.of(ok("P1", Map.of("BTC", BigDecimal.ONE)))));

        assertEquals(0, BigDecimal.ONE.compareTo(result.get("BTC")));
    }

    @Test
    void sdkBreakdownSkipsCashPositions() {
        SpotPosition btc = new SpotPosition();
        btc.setAsset("btc");
        btc.setAccountUuid("acc-btc");
        btc.setTotalBalanceCrypto(0.5);
        SpotPosition eur = new SpotPosition();
        eur.setAsset("EUR");
        eur.setAccountUuid("acc-eur");
        eur.setCash(true);
        eur.setTotalBalanceCrypto(100);
        List<SpotPosition> positions = new ArrayList<>(List.of(btc, eur));

        CoinbaseService.BreakdownResult result = service.fromSpotPositions("P1", "Default", positions);

        assertTrue(result.ok());
        assertEquals(Set.of("BTC"), result.balances().keySet());
        assertEquals(0, new BigDecimal("0.5").compareTo(result.balances().get("BTC")));
        assertTrue(result.accountUuids().contains("acc-btc"));
    }

    @Test
    void rawBreakdownSkipsCashAndCollectsAccountUuids() {
        String body = """
                {"breakdown": {"spot_positions": [
                  {"asset": "ETH", "account_uuid": "acc-eth", "total_balance_crypto": "2.5", "is_cash": false},
                  {"asset": "USD", "account_uuid": "acc-usd", "total_balance_crypto": 50, "is_cash": true}
                ]}}
                """;

        CoinbaseService.BreakdownResult result = service.parseRawBreakdown("P1", "Default", body);

        assertTrue(result.ok());
        assertEquals(Set.of("ETH"), result.balances().keySet());
        assertEquals(0, new BigDecimal("2.5").compareTo(result.balances().get("ETH")));
        assertTrue(result.accountUuids().contains("acc-eth"));
    }

    @Test
    void rawBreakdownWithoutSpotPositionsIsFailure() {
        assertFalse(service.parseRawBreakdown("P1", "Default", "{\"breakdown\": {}}").ok());
        assertFalse(service.parseRawBreakdown("P1", "Default", "not json").ok());
    }

    @Test
    void accountsArePaginatedWithCursor() {
        ListAccountsResponse first = new ListAccountsResponse.Builder()
                .accounts(List.of(account("a1", "BTC", "1", "P1")))
                .hasNext(true).cursor("c1").build();
        ListAccountsResponse second = new ListAccountsResponse.Builder()
                .accounts(List.of(account("a2", "ETH", "1", "P1")))
                .hasNext(false).build();
        List<String> requestedCursors = new ArrayList<>();

        List<Account> accounts = CoinbaseService.collectAllAccounts(first, cursor -> {
            requestedCursors.add(cursor);
            return second;
        });

        assertEquals(List.of("a1", "a2"), accounts.stream().map(Account::getUuid).toList());
        assertEquals(List.of("c1"), requestedCursors);
    }

    @Test
    void paginationWithoutCursorOrBeyondCapFails() {
        ListAccountsResponse noCursor = new ListAccountsResponse.Builder()
                .accounts(List.of()).hasNext(true).build();
        assertThrows(IllegalStateException.class, () -> CoinbaseService.collectAllAccounts(noCursor, c -> null));

        ListAccountsResponse endless = new ListAccountsResponse.Builder()
                .accounts(List.of()).hasNext(true).cursor("again").build();
        assertThrows(IllegalStateException.class, () -> CoinbaseService.collectAllAccounts(endless, c -> endless));
    }
}
