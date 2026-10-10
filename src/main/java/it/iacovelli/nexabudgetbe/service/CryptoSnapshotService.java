package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.model.CryptoPortfolioSnapshot;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.CryptoHoldingRepository;
import it.iacovelli.nexabudgetbe.repository.CryptoPortfolioSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot giornaliero del valore del portafoglio crypto, per lo storico del patrimonio netto. Gli holdings
 * attuali non permettono di ricostruire il passato: lo storico parte dal primo snapshot.
 */
@Service
public class CryptoSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(CryptoSnapshotService.class);
    private static final int MAX_HISTORY_MONTHS = 60;

    /** Valore alla data, già convertito nella valuta richiesta. */
    public record Point(LocalDate date, BigDecimal value) {
    }

    private final CryptoHoldingRepository holdingRepository;
    private final CryptoPortfolioSnapshotRepository snapshotRepository;
    private final CryptoPortfolioService cryptoPortfolioService;
    private final CurrencyConversionService currencyConversionService;
    private final TransactionTemplate transactionTemplate;

    public CryptoSnapshotService(CryptoHoldingRepository holdingRepository,
                                 CryptoPortfolioSnapshotRepository snapshotRepository,
                                 CryptoPortfolioService cryptoPortfolioService,
                                 CurrencyConversionService currencyConversionService,
                                 PlatformTransactionManager transactionManager) {
        this.holdingRepository = holdingRepository;
        this.snapshotRepository = snapshotRepository;
        this.cryptoPortfolioService = cryptoPortfolioService;
        this.currencyConversionService = currencyConversionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // Dopo il job degli investimenti (23:30)
    @Scheduled(cron = "0 40 23 * * ?")
    public void takeDailySnapshots() {
        LocalDate today = LocalDate.now();
        List<User> users = holdingRepository.findUsersWithHoldings();
        logger.info("[CryptoSnapshot] Utenti con crypto: {}", users.size());
        for (User user : users) {
            try {
                takeSnapshot(user, today);
            } catch (Exception e) {
                // Un utente che fallisce (o un secondo pod che ha già scritto lo snapshot) non blocca gli altri
                logger.warn("[CryptoSnapshot] Snapshot dell'utente {} non salvato: {}", user.getId(), e.getMessage());
            }
        }
    }

    void takeSnapshot(User user, LocalDate date) {
        String currency = user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";
        // Calcolo fuori dalla transazione: chiama Binance
        CryptoDto.PortfolioValueResponse portfolio = cryptoPortfolioService.getPortfolioValue(user, currency);
        // Valuta diversa = cambio non disponibile (il servizio degrada a USD); asset senza prezzo = valore parziale:
        // in entrambi i casi uno snapshot sarebbe un falso crollo
        if (portfolio.getCurrency() == null || !portfolio.getCurrency().equalsIgnoreCase(currency)
                || portfolio.getAssets().stream().anyMatch(a -> a.getValue() == null)) {
            logger.warn("[CryptoSnapshot] Portafoglio dell'utente {} incompleto: snapshot saltato", user.getId());
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            CryptoPortfolioSnapshot snapshot = snapshotRepository.findByUserAndSnapshotDate(user, date)
                    .orElseGet(() -> CryptoPortfolioSnapshot.builder().user(user).snapshotDate(date).build());
            snapshot.setTotalValue(portfolio.getTotalValue());
            snapshot.setCurrency(currency.toUpperCase());
            snapshotRepository.save(snapshot);
        });
    }

    public List<Point> getHistory(User user, int months, String currency) {
        int safeMonths = Math.max(1, Math.min(months, MAX_HISTORY_MONTHS));
        LocalDate today = LocalDate.now();
        List<Point> points = new ArrayList<>();
        for (CryptoPortfolioSnapshot s : snapshotRepository
                .findByUserAndSnapshotDateBetweenOrderBySnapshotDateAsc(user, today.minusMonths(safeMonths), today)) {
            Optional<BigDecimal> rate = currencyConversionService.getRate(s.getCurrency(), currency);
            if (rate.isEmpty()) {
                continue;
            }
            points.add(new Point(s.getSnapshotDate(), s.getTotalValue().multiply(rate.get()).setScale(2, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
