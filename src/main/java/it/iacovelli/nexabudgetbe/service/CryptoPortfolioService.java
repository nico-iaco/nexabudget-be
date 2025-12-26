package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.dto.CryptoHoldingDto;
import it.iacovelli.nexabudgetbe.model.CryptoHolding;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.model.UserBinanceKeys;
import it.iacovelli.nexabudgetbe.repository.CryptoHoldingRepository;
import it.iacovelli.nexabudgetbe.repository.UserBinanceKeysRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CryptoPortfolioService {

    private final CryptoHoldingRepository holdingRepository;
    private final UserBinanceKeysRepository keysRepository;
    private final BinanceService binanceService;
    private final CurrencyConversionService currencyConversionService;
    private static final Logger log = LoggerFactory.getLogger(CryptoPortfolioService.class);

    public CryptoPortfolioService(CryptoHoldingRepository holdingRepository,
            UserBinanceKeysRepository keysRepository,
            BinanceService binanceService,
            CurrencyConversionService currencyConversionService) {
        this.holdingRepository = holdingRepository;
        this.keysRepository = keysRepository;
        this.binanceService = binanceService;
        this.currencyConversionService = currencyConversionService;
    }

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id")
    public CryptoHoldingDto addManualHolding(User user, String symbol, BigDecimal amount) {
        Optional<CryptoHolding> existing = holdingRepository.findByUserAndSymbolAndSource(
                user, symbol, HoldingSource.MANUAL);

        CryptoHolding holding = existing.orElseGet(CryptoHolding::new);
        holding.setUser(user);
        holding.setSymbol(symbol.toUpperCase());
        holding.setAmount(amount);
        holding.setSource(HoldingSource.MANUAL);

        CryptoHolding cryptoHolding = holdingRepository.save(holding);

        return mapEntityToDto(cryptoHolding);
    }

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id")
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

    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id")
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

    @Async
    @CacheEvict(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id")
    public void syncBinanceHoldings(User user) {
        UserBinanceKeys keys = keysRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chiavi Binance non configurate"));

        List<CryptoBalance> binanceBalances = binanceService.getAllWalletsIncludingEarn(keys.getApiKey(),
                keys.getApiSecret());

        // Recupera i vecchi holdings da eliminare
        List<CryptoHolding> oldHoldings = holdingRepository.findByUser(user).stream()
                .filter(h -> h.getSource() == HoldingSource.BINANCE)
                .toList();

        // Elimina i vecchi holdings Binance in batch e forza il flush
        if (!oldHoldings.isEmpty()) {
            holdingRepository.deleteAllInBatch(oldHoldings);
            holdingRepository.flush();
        }

        // Crea i nuovi holdings
        List<CryptoHolding> holdingsToSave = binanceBalances.stream()
                .map(balance -> CryptoHolding.builder()
                        .user(user)
                        .symbol(balance.getSymbol().toUpperCase())
                        .amount(balance.getAmount())
                        .source(HoldingSource.BINANCE)
                        .build())
                .toList();

        holdingRepository.saveAll(holdingsToSave);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.PORTFOLIO_CACHE, key = "#user.id")
    public CryptoDto.PortfolioValueResponse getPortfolioValue(User user, String currency) {
        List<CryptoHolding> holdings = holdingRepository.findByUser(user);

        // Identifica i simboli unici per recuperare i prezzi una volta sola
        List<String> uniqueSymbols = holdings.stream()
                .map(CryptoHolding::getSymbol)
                .distinct()
                .toList();

        // Mappa Simbolo -> Prezzo USD
        Map<String, BigDecimal> pricesMap = uniqueSymbols.stream()
                .collect(Collectors.toMap(
                        symbol -> symbol,
                        symbol -> {
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

        // Converti il totale
        BigDecimal totalValueConverted = currencyConversionService.convertFromUsd(totalValueUsd, targetCurrency);

        // Converti ogni asset
        List<CryptoDto.AssetValue> convertedAssets = assetValuesUsd.stream()
                .map(asset -> new CryptoDto.AssetValue(
                        asset.getId(),
                        asset.getSource(),
                        asset.getSymbol(),
                        asset.getAmount(),
                        currencyConversionService.convertFromUsd(asset.getPrice(), targetCurrency),
                        currencyConversionService.convertFromUsd(asset.getValue(), targetCurrency)))
                .collect(Collectors.toList());

        return new CryptoDto.PortfolioValueResponse(totalValueConverted, targetCurrency.toUpperCase(), convertedAssets);
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
