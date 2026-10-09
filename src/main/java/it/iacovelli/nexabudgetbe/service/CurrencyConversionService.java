package it.iacovelli.nexabudgetbe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Servizio di alto livello per conversione importi tra valute.
 * Delegata la responsabilità di recupero (e caching) dei tassi a {@link ExchangeRateService}.
 */
@Service
public class CurrencyConversionService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyConversionService.class);
    private static final String USD_CURRENCY = "USD";

    private final ExchangeRateService exchangeRateService;

    public CurrencyConversionService(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * Converte un importo da USD alla valuta target (wrapper specifico per casi correnti).
     */
    public BigDecimal convertFromUsd(BigDecimal amountUsd, String targetCurrency) {
        return convert(amountUsd, USD_CURRENCY, targetCurrency);
    }

    /**
     * Come {@link #convertFromUsd} ma con la scala indicata: per i prezzi unitari (es. token da $0.000012)
     * la scala 2 li azzererebbe.
     */
    public BigDecimal convertFromUsd(BigDecimal amountUsd, String targetCurrency, int scale) {
        return convert(amountUsd, USD_CURRENCY, targetCurrency, scale);
    }

    /**
     * Tasso sourceCurrency -> targetCurrency, vuoto se non disponibile (1 se le valute coincidono).
     * A differenza di {@link #convert}, permette al chiamante di sapere se la conversione è avvenuta davvero
     * invece di ricevere l'importo originale.
     */
    public Optional<BigDecimal> getRate(String sourceCurrency, String targetCurrency) {
        if (sourceCurrency == null || targetCurrency == null || sourceCurrency.isBlank() || targetCurrency.isBlank()) {
            return Optional.empty();
        }
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return Optional.of(BigDecimal.ONE);
        }
        return exchangeRateService.getRate(sourceCurrency.toUpperCase(), targetCurrency.toUpperCase());
    }

    /**
     * Converte un importo da sourceCurrency a targetCurrency.
     * Se currencies uguali o amount nullo -> ritorna amount.
     * Se tasso non disponibile -> ritorna amount (fallback) e logga warning.
     */
    public BigDecimal convert(BigDecimal amount, String sourceCurrency, String targetCurrency) {
        return convert(amount, sourceCurrency, targetCurrency, 2);
    }

    public BigDecimal convert(BigDecimal amount, String sourceCurrency, String targetCurrency, int scale) {
        if (amount == null || sourceCurrency == null || targetCurrency == null ||
                sourceCurrency.isBlank() || targetCurrency.isBlank()) {
            return amount;
        }
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return amount;
        }

        Optional<BigDecimal> rateOpt = exchangeRateService.getRate(sourceCurrency.toUpperCase(), targetCurrency.toUpperCase());
        if (rateOpt.isPresent()) {
            return amount.multiply(rateOpt.get()).setScale(scale, RoundingMode.HALF_UP);
        }
        logger.warn("Tasso {}->{} non disponibile. Ritorno valore originale.", sourceCurrency, targetCurrency);
        return amount;
    }
}
