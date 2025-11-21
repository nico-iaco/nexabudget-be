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
     * Converte un importo da sourceCurrency a targetCurrency.
     * Se currencies uguali o amount nullo -> ritorna amount.
     * Se tasso non disponibile -> ritorna amount (fallback) e logga warning.
     */
    public BigDecimal convert(BigDecimal amount, String sourceCurrency, String targetCurrency) {
        if (amount == null || sourceCurrency == null || targetCurrency == null ||
                sourceCurrency.isBlank() || targetCurrency.isBlank()) {
            return amount;
        }
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return amount;
        }

        Optional<BigDecimal> rateOpt = exchangeRateService.getRate(sourceCurrency.toUpperCase(), targetCurrency.toUpperCase());
        if (rateOpt.isPresent()) {
            return amount.multiply(rateOpt.get()).setScale(2, RoundingMode.HALF_UP);
        }
        logger.warn("Tasso {}->{} non disponibile. Ritorno valore originale.", sourceCurrency, targetCurrency);
        return amount;
    }
}
