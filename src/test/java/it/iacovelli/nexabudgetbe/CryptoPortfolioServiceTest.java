package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.model.CryptoHolding;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.CryptoHoldingRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.BinanceService;
import it.iacovelli.nexabudgetbe.service.CryptoPortfolioService;
import it.iacovelli.nexabudgetbe.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class CryptoPortfolioServiceTest {

    @Autowired
    private CryptoPortfolioService cryptoPortfolioService;

    @Autowired
    private CryptoHoldingRepository holdingRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private BinanceService binanceService;

    @MockBean
    private ExchangeRateService exchangeRateService;

    private User testUser;

    @BeforeEach
    public void setup() {
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("test@test.com");
        testUser.setPasswordHash("password");

        // Salva l'utente nel database
        testUser = userRepository.save(testUser);

        // Mock Binance prices
        when(binanceService.getTickerPrice("BTC"))
                .thenReturn(Optional.of(new BigDecimal("30000.00")));
        when(binanceService.getTickerPrice("ETH"))
                .thenReturn(Optional.of(new BigDecimal("2000.00")));

        // Mock exchange rate per EUR (USD -> EUR = 0.92)
        when(exchangeRateService.getRate("USD", "EUR"))
                .thenReturn(Optional.of(new BigDecimal("0.92")));
        // Fallback: per qualsiasi altra valuta richiamata nei test, ritorna empty così rimane USD
        when(exchangeRateService.getRate(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String src = inv.getArgument(0);
                    String tgt = inv.getArgument(1);
                    if ("USD".equals(src) && "EUR".equals(tgt)) {
                        return Optional.of(new BigDecimal("0.92"));
                    }
                    return Optional.empty();
                });
    }

    @Test
    public void testAddManualHolding() {
        // Arrange
        String symbol = "BTC";
        BigDecimal amount = new BigDecimal("0.5");

        // Act
        CryptoHolding holding = cryptoPortfolioService.addManualHolding(testUser, symbol, amount);

        // Assert
        assertNotNull(holding);
        assertNotNull(holding.getId());
        assertEquals(symbol.toUpperCase(), holding.getSymbol());
        assertEquals(amount, holding.getAmount());
        assertEquals(HoldingSource.MANUAL, holding.getSource());
        assertEquals(testUser, holding.getUser());
    }

    @Test
    public void testUpdateManualHolding() {
        // Arrange - First add
        String symbol = "BTC";
        BigDecimal initialAmount = new BigDecimal("0.5");
        CryptoHolding firstHolding = cryptoPortfolioService.addManualHolding(testUser, symbol, initialAmount);

        // Act - Update with new amount
        BigDecimal newAmount = new BigDecimal("1.0");
        CryptoHolding updatedHolding = cryptoPortfolioService.addManualHolding(testUser, symbol, newAmount);

        // Assert
        assertEquals(firstHolding.getId(), updatedHolding.getId());
        assertEquals(newAmount, updatedHolding.getAmount());
    }

    @Test
    public void testGetPortfolioValue() {
        // Arrange
        cryptoPortfolioService.addManualHolding(testUser, "BTC", new BigDecimal("0.5"));
        cryptoPortfolioService.addManualHolding(testUser, "ETH", new BigDecimal("2.0"));

        // Act
        CryptoDto.PortfolioValueResponse response = cryptoPortfolioService.getPortfolioValue(testUser, "USD");

        // Assert
        assertNotNull(response);
        assertEquals("USD", response.getCurrency());
        assertEquals(2, response.getAssets().size());

        // BTC: 0.5 * 30000 = 15000
        // ETH: 2.0 * 2000 = 4000
        // Total: 19000
        BigDecimal expectedTotal = new BigDecimal("19000.00");
        assertEquals(0, expectedTotal.compareTo(response.getTotalValue()));
    }

    @Test
    public void testGetPortfolioValueWithMultipleSources() {
        // Arrange - Add manual holding
        CryptoHolding manualHolding = CryptoHolding.builder()
                .user(testUser)
                .symbol("BTC")
                .amount(new BigDecimal("0.3"))
                .source(HoldingSource.MANUAL)
                .build();
        holdingRepository.save(manualHolding);

        // Add Binance holding (simulating sync)
        CryptoHolding binanceHolding = CryptoHolding.builder()
                .user(testUser)
                .symbol("BTC")
                .amount(new BigDecimal("0.2"))
                .source(HoldingSource.BINANCE)
                .build();
        holdingRepository.save(binanceHolding);

        // Act
        CryptoDto.PortfolioValueResponse response = cryptoPortfolioService.getPortfolioValue(testUser, "USD");

        // Assert
        assertNotNull(response);
        assertEquals(1, response.getAssets().size());

        CryptoDto.AssetValue btcAsset = response.getAssets().getFirst();
        assertEquals("BTC", btcAsset.getSymbol());

        // Total BTC: 0.3 + 0.2 = 0.5
        BigDecimal expectedAmount = new BigDecimal("0.5");
        assertEquals(0, expectedAmount.compareTo(btcAsset.getAmount()));

        // Value: 0.5 * 30000 = 15000
        BigDecimal expectedValue = new BigDecimal("15000.00");
        assertEquals(0, expectedValue.compareTo(btcAsset.getValue()));
    }

    @Test
    public void testGetPortfolioValueWithUnknownPrice() {
        // Arrange
        when(binanceService.getTickerPrice("UNKNOWN"))
                .thenReturn(Optional.empty());

        cryptoPortfolioService.addManualHolding(testUser, "BTC", new BigDecimal("0.5"));
        cryptoPortfolioService.addManualHolding(testUser, "UNKNOWN", new BigDecimal("100"));

        // Act
        CryptoDto.PortfolioValueResponse response = cryptoPortfolioService.getPortfolioValue(testUser, "USD");

        // Assert
        assertNotNull(response);
        // Only BTC should be included (UNKNOWN has no price)
        assertEquals(1, response.getAssets().size());
        assertEquals("BTC", response.getAssets().get(0).getSymbol());
    }

    @Test
    public void testGetPortfolioValueWithCurrencyConversion() {
        // Arrange
        cryptoPortfolioService.addManualHolding(testUser, "BTC", new BigDecimal("1.0"));

        // Act - Request in EUR
        CryptoDto.PortfolioValueResponse response = cryptoPortfolioService.getPortfolioValue(testUser, "EUR");

        // Assert
        assertNotNull(response);
        assertEquals("EUR", response.getCurrency());
        assertEquals(1, response.getAssets().size());

        // BTC: 1.0 * 30000 USD = 30000 USD
        // Converted to EUR: 30000 * 0.92 = 27600 EUR
        CryptoDto.AssetValue btcAsset = response.getAssets().getFirst();
        assertEquals("BTC", btcAsset.getSymbol());

        BigDecimal expectedPriceEur = new BigDecimal("27600.00");
        assertEquals(0, expectedPriceEur.compareTo(btcAsset.getPrice()));
        assertEquals(0, expectedPriceEur.compareTo(response.getTotalValue()));
    }
}

