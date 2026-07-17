package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.controller.BankingController;
import it.iacovelli.nexabudgetbe.service.EnableBankingService;
import it.iacovelli.nexabudgetbe.service.bank.BankAggregationProvider;
import it.iacovelli.nexabudgetbe.service.bank.GocardlessAggregationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enable Banking è un provider opzionale: l'applicazione deve avviarsi normalmente anche quando
 * ENABLEBANKING_APP_ID/ENABLEBANKING_PRIVATE_KEY non sono configurati (es. deployment che usano
 * solo GoCardless). Questo test usa un ApplicationContext dedicato (proprietà sovrascritte via
 * @TestPropertySource) per riprodurre esattamente quello scenario, a differenza degli altri test
 * che ereditano la chiave RSA di test valida da application-test.properties.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "enablebanking.appId=",
        "enablebanking.privateKey="
})
class EnableBankingOptionalStartupTest {

    @Autowired
    private EnableBankingService enableBankingService;

    @Autowired
    private BankingController bankingController;

    @Autowired
    private List<BankAggregationProvider> bankAggregationProviders;

    @Test
    void applicationContextLoads_evenWithoutEnableBankingCredentials() {
        // Se il context non si fosse avviato, @Autowired sopra avrebbe già fallito il test.
        assertNotNull(enableBankingService);
        assertNotNull(bankingController);
    }

    @Test
    void enableBankingService_reportsNotConfigured() {
        assertFalse(enableBankingService.isConfigured());
    }

    @Test
    void gocardlessProvider_stillRegisteredAndUsable() {
        boolean hasGocardless = bankAggregationProviders.stream()
                .anyMatch(GocardlessAggregationProvider.class::isInstance);
        assertTrue(hasGocardless, "GoCardless deve restare disponibile anche se Enable Banking non è configurato");
    }

    @Test
    void enableBankingEndpoint_returns503InsteadOfCrashing() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> bankingController.getBanks("enable-banking", "IT"));
        assertEquals(503, ex.getStatusCode().value());
    }
}
