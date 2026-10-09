package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.dto.CryptoHoldingDto;
import it.iacovelli.nexabudgetbe.model.CryptoHolding;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.model.UserBinanceKeys;
import it.iacovelli.nexabudgetbe.model.UserCoinbaseKeys;
import it.iacovelli.nexabudgetbe.repository.CryptoHoldingRepository;
import it.iacovelli.nexabudgetbe.repository.UserBinanceKeysRepository;
import it.iacovelli.nexabudgetbe.repository.UserCoinbaseKeysRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class CryptoPortfolioService {

    private final CryptoHoldingRepository holdingRepository;
    private final UserBinanceKeysRepository keysRepository;
    private final UserCoinbaseKeysRepository coinbaseKeysRepository;
    private final BinanceService binanceService;
    private final CoinbaseService coinbaseService;
    private final CurrencyConversionService currencyConversionService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger log = LoggerFactory.getLogger(CryptoPortfolioService.class);

    // Scadenza del lock di sync: una chiamata all'exchange appesa non deve bloccare l'utente per sempre
    static final Duration SYNC_LOCK_TIMEOUT = Duration.ofMinutes(10);

    // Sync in corso per utente+sorgente. Lock in memoria, quindi per-pod: due pod diversi possono ancora
    // sincronizzare in parallelo lo stesso utente (delete+insert restano atomici, ma con possibili duplicati)
    private final ConcurrentHashMap<String, SyncLock> syncLocks = new ConcurrentHashMap<>();

    // Classe e non record: il rilascio confronta per identità (due lock presi nello stesso istante sarebbero equals)
    private static final class SyncLock {
        private final Instant startedAt;

        private SyncLock(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    public CryptoPortfolioService(CryptoHoldingRepository holdingRepository,
            UserBinanceKeysRepository keysRepository,
            UserCoinbaseKeysRepository coinbaseKeysRepository,
            BinanceService binanceService,
            CoinbaseService coinbaseService,
            CurrencyConversionService currencyConversionService,
            PlatformTransactionManager transactionManager) {
        this.holdingRepository = holdingRepository;
        this.keysRepository = keysRepository;
        this.coinbaseKeysRepository = coinbaseKeysRepository;
        this.binanceService = binanceService;
        this.coinbaseService = coinbaseService;
        this.currencyConversionService = currencyConversionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, allEntries = true)
    public CryptoHoldingDto addManualHolding(User user, String symbol, BigDecimal amount) {
        Optional<CryptoHolding> existing = holdingRepository.findByUserAndSymbolAndSource(
                user, symbol.toUpperCase(), HoldingSource.MANUAL);

        CryptoHolding holding = existing.orElseGet(CryptoHolding::new);
        holding.setUser(user);
        holding.setSymbol(symbol.toUpperCase());
        holding.setAmount(amount);
        holding.setSource(HoldingSource.MANUAL);

        CryptoHolding cryptoHolding = holdingRepository.save(holding);

        return mapEntityToDto(cryptoHolding);
    }

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, allEntries = true)
    public CryptoHoldingDto updateManualHolding(User user, UUID holdingId, BigDecimal newAmount) {
        CryptoHolding holding = holdingRepository.findById(holdingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset non trovato"));

        if (!holding.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Non sei autorizzato a modificare questo asset");
        }

        if (holding.getSource() != HoldingSource.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Puoi modificare solo asset manuali");
        }

        holding.setAmount(newAmount);
        CryptoHolding updated = holdingRepository.save(holding);

        return mapEntityToDto(updated);
    }

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, allEntries = true)
    public void deleteManualHolding(User user, UUID holdingId) {
        CryptoHolding holding = holdingRepository.findById(holdingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset non trovato"));

        if (!holding.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Non sei autorizzato a eliminare questo asset");
        }

        if (holding.getSource() != HoldingSource.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Puoi eliminare solo asset manuali");
        }

        holdingRepository.delete(holding);
    }

    @Transactional
    public void saveBinanceKeys(User user, String apiKey, String apiSecret) {
        Optional<UserBinanceKeys> existing = keysRepository.findByUser(user);

        UserBinanceKeys keys = existing.orElseGet(UserBinanceKeys::new);
        keys.setUser(user);
        keys.setApiKey(apiKey);
        keys.setApiSecret(apiSecret);

        keysRepository.save(keys);
    }

    @Transactional
    public void saveCoinbaseKeys(User user, String apiKeyName, String privateKey) {
        Optional<UserCoinbaseKeys> existing = coinbaseKeysRepository.findByUser(user);

        UserCoinbaseKeys keys = existing.orElseGet(UserCoinbaseKeys::new);
        keys.setUser(user);
        keys.setApiKeyName(apiKeyName);
        keys.setPrivateKey(privateKey);

        coinbaseKeysRepository.save(keys);
    }

    /**
     * Verifica sincrona delle chiavi Binance, da chiamare prima di {@link #syncBinanceHoldings}: l'eccezione
     * lanciata dentro il metodo @Async resterebbe nel thread asincrono e il client riceverebbe comunque 202.
     */
    public void requireBinanceKeys(User user) {
        if (keysRepository.findByUser(user).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiavi Binance non configurate");
        }
    }

    /**
     * Come {@link #requireBinanceKeys}, per Coinbase.
     */
    public void requireCoinbaseKeys(User user) {
        if (coinbaseKeysRepository.findByUser(user).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiavi Coinbase non configurate");
        }
    }

    @Async
    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, allEntries = true)
    public void syncBinanceHoldings(User user) {
        UserBinanceKeys keys = keysRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiavi Binance non configurate"));

        runExclusiveSync(user, HoldingSource.BINANCE,
                () -> binanceService.getAllWalletsIncludingEarn(keys.getApiKey(), keys.getApiSecret()));
    }

    @Async
    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, allEntries = true)
    public void syncCoinbaseHoldings(User user) {
        UserCoinbaseKeys keys = coinbaseKeysRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiavi Coinbase non configurate"));

        runExclusiveSync(user, HoldingSource.COINBASE,
                () -> coinbaseService.getWallets(keys.getApiKeyName(), keys.getPrivateKey()));
    }

    /**
     * Sync di una sorgente: prima il recupero dall'exchange (fuori da qualsiasi transazione, può durare secondi),
     * poi delete + insert degli holdings nella stessa transazione. Se il recupero fallisce non si cancella nulla;
     * se l'insert fallisce il rollback ripristina i vecchi holdings.
     * Una sync già in corso per lo stesso utente+sorgente fa ignorare quella nuova (es. doppio click).
     */
    private void runExclusiveSync(User user, HoldingSource source, Supplier<List<CryptoBalance>> fetcher) {
        String lockKey = user.getId() + ":" + source;
        SyncLock token = new SyncLock(Instant.now());
        SyncLock current = syncLocks.compute(lockKey, (k, existing) ->
                existing == null || existing.startedAt.isBefore(token.startedAt.minus(SYNC_LOCK_TIMEOUT)) ? token : existing);
        if (current != token) {
            log.info("Sync {} già in corso per l'utente {}: richiesta ignorata", source, user.getId());
            return;
        }

        try {
            List<CryptoBalance> balances = fetcher.get();
            int saved = replaceHoldings(user, source, balances);
            log.info("Sync {} completata per l'utente {}: {} holdings", source, user.getId(), saved);
        } finally {
            // remove(key, value): non rilascia un lock scaduto e già ripreso da un'altra sync
            syncLocks.remove(lockKey, token);
        }
    }

    private int replaceHoldings(User user, HoldingSource source, List<CryptoBalance> balances) {
        // Accorpa per simbolo maiuscolo: "btc" e "BTC" diventerebbero due holdings
        Map<String, BigDecimal> amountsBySymbol = new LinkedHashMap<>();
        for (CryptoBalance balance : balances) {
            if (balance.getSymbol() == null || balance.getAmount() == null) {
                continue;
            }
            amountsBySymbol.merge(balance.getSymbol().toUpperCase(), balance.getAmount(), BigDecimal::add);
        }

        List<CryptoHolding> holdingsToSave = amountsBySymbol.entrySet().stream()
                .map(e -> CryptoHolding.builder()
                        .user(user)
                        .symbol(e.getKey())
                        .amount(e.getValue())
                        .source(source)
                        .build())
                .toList();

        // TransactionTemplate e non un metodo @Transactional: chiamato da questo stesso bean bypasserebbe il proxy
        transactionTemplate.executeWithoutResult(status -> {
            holdingRepository.bulkDeleteByUserAndSource(user, source);
            holdingRepository.saveAll(holdingsToSave);
        });
        return holdingsToSave.size();
    }

    @Transactional(readOnly = true)
    // La chiave include la valuta: altrimenti un portfolio calcolato in USD veniva restituito anche a chi chiede EUR.
    // unless: se manca il tasso la risposta degrada a USD e non va cachata (si riprova alla richiesta successiva)
    @Cacheable(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id + '_' + (#currency == null ? '' : #currency.toUpperCase())",
            unless = "#currency != null && !#currency.equalsIgnoreCase(#result.currency)")
    public CryptoDto.PortfolioValueResponse getPortfolioValue(User user, String currency) {
        List<CryptoHolding> holdings = holdingRepository.findByUser(user);

        // Identifica i simboli unici per recuperare i prezzi una volta sola
        List<String> uniqueSymbols = holdings.stream()
                .map(CryptoHolding::getSymbol)
                .distinct()
                .toList();

        // Recupera tutti i prezzi in batch (1 chiamata API invece di N)
        Map<String, BigDecimal> batchPrices = binanceService.getAllTickerPricesUsdt();

        // Mappa Simbolo -> Prezzo USD (ZERO = prezzo non disponibile), con fallback per simboli non in batch
        Map<String, BigDecimal> pricesMap = uniqueSymbols.stream()
                .collect(Collectors.toMap(
                        symbol -> symbol,
                        symbol -> {
                            BigDecimal batchPrice = batchPrices.get(symbol);
                            if (batchPrice != null) {
                                return batchPrice;
                            }
                            try {
                                return binanceService.getTickerPrice(symbol).orElse(BigDecimal.ZERO);
                            } catch (Exception e) {
                                log.error("Errore durante il recupero del prezzo del token {}", symbol, e);
                                return BigDecimal.ZERO;
                            }
                        }));

        BigDecimal totalValueUsd = BigDecimal.ZERO;
        List<CryptoDto.AssetValue> assetValues = new ArrayList<>();

        for (CryptoHolding holding : holdings) {
            BigDecimal priceUsd = pricesMap.getOrDefault(holding.getSymbol(), BigDecimal.ZERO);

            if (priceUsd.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal assetValueUsd = holding.getAmount().multiply(priceUsd);
                totalValueUsd = totalValueUsd.add(assetValueUsd);

                assetValues.add(new CryptoDto.AssetValue(
                        holding.getId(),
                        holding.getSource(),
                        holding.getSymbol(),
                        holding.getAmount(),
                        priceUsd,
                        assetValueUsd));
            } else {
                // Senza prezzo (es. simbolo manuale digitato male) l'asset resta in elenco con prezzo/valore null,
                // così può ancora essere modificato o eliminato; non entra nel totale
                log.debug("Prezzo non disponibile per {}: asset incluso senza valore", holding.getSymbol());
                assetValues.add(new CryptoDto.AssetValue(
                        holding.getId(),
                        holding.getSource(),
                        holding.getSymbol(),
                        holding.getAmount(),
                        null,
                        null));
            }
        }

        // Converti i valori nella valuta richiesta se non è USD
        if (currency != null && !currency.equalsIgnoreCase("USD")) {
            return convertToTargetCurrency(totalValueUsd, assetValues, currency);
        }

        return new CryptoDto.PortfolioValueResponse(totalValueUsd, "USD", assetValues);
    }

    private CryptoDto.PortfolioValueResponse convertToTargetCurrency(
            BigDecimal totalValueUsd,
            List<CryptoDto.AssetValue> assetValuesUsd,
            String targetCurrency) {

        // Senza tasso convertFromUsd restituirebbe gli importi in USD: etichettarli come targetCurrency sarebbe sbagliato
        Optional<BigDecimal> rateOpt = currencyConversionService.getRate("USD", targetCurrency);
        if (rateOpt.isEmpty()) {
            log.warn("Tasso USD->{} non disponibile: portafoglio crypto restituito in USD", targetCurrency);
            return new CryptoDto.PortfolioValueResponse(totalValueUsd, "USD", assetValuesUsd);
        }
        BigDecimal rate = rateOpt.get();

        // Converti il totale
        BigDecimal totalValueConverted = convertAmount(totalValueUsd, rate, 2);

        // Converti ogni asset (scala 8 per i prezzi unitari: la scala 2 azzererebbe i token sotto il centesimo)
        List<CryptoDto.AssetValue> convertedAssets = assetValuesUsd.stream()
                .map(asset -> new CryptoDto.AssetValue(
                        asset.getId(),
                        asset.getSource(),
                        asset.getSymbol(),
                        asset.getAmount(),
                        convertAmount(asset.getPrice(), rate, 8),
                        convertAmount(asset.getValue(), rate, 2)))
                .collect(Collectors.toList());

        return new CryptoDto.PortfolioValueResponse(totalValueConverted, targetCurrency.toUpperCase(), convertedAssets);
    }

    private static BigDecimal convertAmount(BigDecimal amountUsd, BigDecimal rate, int scale) {
        return amountUsd == null ? null : amountUsd.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    private CryptoHoldingDto mapEntityToDto(CryptoHolding cryptoHolding) {
        CryptoHoldingDto cryptoHoldingDto = new CryptoHoldingDto();
        cryptoHoldingDto.setId(cryptoHolding.getId());
        cryptoHoldingDto.setAmount(cryptoHolding.getAmount());
        cryptoHoldingDto.setSymbol(cryptoHolding.getSymbol());
        cryptoHoldingDto.setSource(cryptoHolding.getSource());
        return cryptoHoldingDto;
    }
}
