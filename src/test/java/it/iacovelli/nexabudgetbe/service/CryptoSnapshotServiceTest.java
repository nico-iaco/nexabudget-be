package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class CryptoSnapshotServiceTest {

    @Autowired
    private CryptoSnapshotService service;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private CryptoPortfolioService cryptoPortfolioService;
    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private User user;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setUsername("cryptoholder");
        u.setEmail("cryptoholder@test.com");
        u.setPasswordHash("password");
        u.setDefaultCurrency("EUR");
        user = userRepository.save(u);
        when(exchangeRateService.getRate(anyString(), anyString())).thenAnswer(inv ->
                "EUR".equals(inv.getArgument(0)) && "USD".equals(inv.getArgument(1))
                        ? Optional.of(new BigDecimal("1.1")) : Optional.empty());
    }

    private static CryptoDto.AssetValue asset(String value) {
        return CryptoDto.AssetValue.builder().source(HoldingSource.MANUAL).symbol("BTC")
                .value(value != null ? new BigDecimal(value) : null).build();
    }

    private void portfolio(String currency, String total, CryptoDto.AssetValue... assets) {
        when(cryptoPortfolioService.getPortfolioValue(user, "EUR")).thenReturn(CryptoDto.PortfolioValueResponse.builder()
                .currency(currency).totalValue(new BigDecimal(total)).assets(List.of(assets)).build());
    }

    @Test
    void snapshot_isSavedIdempotentlyAndAppearsInHistory() {
        portfolio("EUR", "2500", asset("2500"));
        service.takeSnapshot(user, today);
        portfolio("EUR", "2600", asset("2600"));
        service.takeSnapshot(user, today); // stesso giorno = aggiornamento, non una seconda riga

        List<CryptoSnapshotService.Point> history = service.getHistory(user, 12, "EUR");

        assertEquals(1, history.size());
        assertEquals(0, new BigDecimal("2600").compareTo(history.get(0).value()));
    }

    @Test
    void history_isConvertedToTheRequestedCurrency() {
        portfolio("EUR", "1000", asset("1000"));
        service.takeSnapshot(user, today);

        assertEquals(0, new BigDecimal("1100").compareTo(service.getHistory(user, 12, "USD").get(0).value()));
        // Senza tasso il punto non è esprimibile: si salta invece di mostrarlo in un'altra valuta
        assertTrue(service.getHistory(user, 12, "CHF").isEmpty());
    }

    @Test
    void snapshot_skippedWhenValueIsInAnotherCurrency() {
        // Il servizio crypto degrada a USD se manca il cambio: salvarlo come EUR falserebbe lo storico
        portfolio("USD", "2500", asset("2500"));

        service.takeSnapshot(user, today);

        assertTrue(service.getHistory(user, 12, "EUR").isEmpty());
    }

    @Test
    void snapshot_skippedWhenAnAssetHasNoPrice() {
        portfolio("EUR", "1000", asset("1000"), asset(null));

        service.takeSnapshot(user, today);

        assertTrue(service.getHistory(user, 12, "EUR").isEmpty());
    }
}
