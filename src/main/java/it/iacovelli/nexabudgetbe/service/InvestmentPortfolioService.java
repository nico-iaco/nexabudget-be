package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.InvestmentAssetRepository;
import it.iacovelli.nexabudgetbe.repository.InvestmentOperationRepository;
import it.iacovelli.nexabudgetbe.repository.InvestmentPortfolioSnapshotRepository;
import it.iacovelli.nexabudgetbe.service.investment.PositionCalculator;
import it.iacovelli.nexabudgetbe.service.investment.PositionCalculator.Position;
import it.iacovelli.nexabudgetbe.service.market.MarketPriceService;
import it.iacovelli.nexabudgetbe.service.market.MarketPriceService.ResolvedPrice;
import it.iacovelli.nexabudgetbe.service.market.MarketQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Portafoglio investimenti (ETF, azioni, obbligazioni, fondi): asset, operazioni, posizioni valorizzate a prezzi
 * di mercato, performance e storico. Gli investimenti non generano transazioni sui conti: restano separati dai
 * report di entrate/uscite.
 */
@Service
public class InvestmentPortfolioService {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentPortfolioService.class);
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // Uno snapshot più vecchio di così non rappresenta il valore "a quella data" (il job gira ogni giorno)
    private static final int SNAPSHOT_MAX_AGE_DAYS = 7;
    private static final int MAX_HISTORY_MONTHS = 120;

    private final InvestmentAssetRepository assetRepository;
    private final InvestmentOperationRepository operationRepository;
    private final InvestmentPortfolioSnapshotRepository snapshotRepository;
    private final MarketPriceService marketPriceService;
    private final CurrencyConversionService currencyConversionService;
    private final TransactionTemplate transactionTemplate;

    public InvestmentPortfolioService(InvestmentAssetRepository assetRepository,
                                      InvestmentOperationRepository operationRepository,
                                      InvestmentPortfolioSnapshotRepository snapshotRepository,
                                      MarketPriceService marketPriceService,
                                      CurrencyConversionService currencyConversionService,
                                      PlatformTransactionManager transactionManager) {
        this.assetRepository = assetRepository;
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
        this.marketPriceService = marketPriceService;
        this.currencyConversionService = currencyConversionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ─── Ricerca ────────────────────────────────────────────────────────────────

    public List<InvestmentDto.SearchResult> search(String query) {
        return marketPriceService.search(query).stream()
                .map(r -> InvestmentDto.SearchResult.builder()
                        .symbol(r.getSymbol())
                        .name(r.getName())
                        .exchange(r.getExchange())
                        .suggestedType(r.getSuggestedType())
                        .provider(r.getProvider())
                        .build())
                .toList();
    }

    // ─── Asset ──────────────────────────────────────────────────────────────────

    // Non @Transactional: la ricerca della valuta chiama un provider HTTP e non deve tenere occupata una connessione
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public InvestmentDto.AssetResponse createAsset(User user, InvestmentDto.AssetRequest request) {
        String isin = normalizeIsin(request.getIsin());
        String symbol = normalizeSymbol(request.getSymbol());
        PriceSource source = request.getPriceSource() != null
                ? request.getPriceSource()
                : (symbol != null ? PriceSource.YAHOO : PriceSource.MANUAL);
        if (source != PriceSource.MANUAL && symbol == null) {
            throw new IllegalArgumentException("Il simbolo è obbligatorio per i prezzi automatici");
        }

        String currency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : null;
        if (currency == null) {
            currency = symbol == null ? null
                    : marketPriceService.getLiveQuote(symbol, source).map(MarketQuote::getCurrency).orElse(null);
            if (currency == null) {
                throw new IllegalArgumentException(
                        "Impossibile determinare la valuta dello strumento: indicala esplicitamente");
            }
        }

        checkUnique(user, null, isin, symbol);

        InvestmentAssetType type = request.getAssetType();
        InvestmentAsset asset = InvestmentAsset.builder()
                .user(user)
                .assetType(type)
                .name(request.getName().trim())
                .isin(isin)
                .symbol(symbol)
                .currency(currency)
                .priceSource(source)
                .build();
        if (request.getManualPrice() != null) {
            asset.setManualPrice(request.getManualPrice());
            asset.setManualPriceAt(LocalDateTime.now());
        }
        applyBondFields(asset, type, request.getCouponRate(), request.getCouponFrequency(), request.getMaturityDate());
        return toAssetResponse(assetRepository.save(asset));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public InvestmentDto.AssetResponse updateAsset(User user, UUID assetId, InvestmentDto.AssetUpdateRequest request) {
        InvestmentAsset asset = getOwnedAsset(user, assetId);
        String isin = normalizeIsin(request.getIsin());
        String symbol = normalizeSymbol(request.getSymbol());
        if (request.getPriceSource() != PriceSource.MANUAL && symbol == null) {
            throw new IllegalArgumentException("Il simbolo è obbligatorio per i prezzi automatici");
        }

        InvestmentAssetType newType = request.getAssetType();
        // Obbligazione ↔ altro tipo cambia il moltiplicatore (prezzo in % del nominale): le operazioni esistenti
        // sarebbero rivalutate in modo errato
        boolean typeChangesPricing = (asset.getAssetType() == InvestmentAssetType.BOND) != (newType == InvestmentAssetType.BOND);
        if (typeChangesPricing && !operationRepository.findByAssetOrderByOperationDateAscCreatedAtAsc(asset).isEmpty()) {
            throw new IllegalStateException(
                    "Non si può passare da/a obbligazione con operazioni già registrate: elimina le operazioni o crea un nuovo asset");
        }
        checkUnique(user, asset.getId(), isin, symbol);

        // Un prezzo noto di un altro strumento o provider non va usato come fallback del nuovo
        if (!Objects.equals(asset.getSymbol(), symbol) || asset.getPriceSource() != request.getPriceSource()) {
            asset.setLastPrice(null);
            asset.setLastPriceCurrency(null);
            asset.setLastPriceAt(null);
        }
        asset.setAssetType(newType);
        asset.setName(request.getName().trim());
        asset.setIsin(isin);
        asset.setSymbol(symbol);
        asset.setPriceSource(request.getPriceSource());
        applyBondFields(asset, newType, request.getCouponRate(), request.getCouponFrequency(), request.getMaturityDate());
        return toAssetResponse(assetRepository.save(asset));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public InvestmentDto.AssetResponse setManualPrice(User user, UUID assetId, BigDecimal price) {
        InvestmentAsset asset = getOwnedAsset(user, assetId);
        asset.setManualPrice(price);
        asset.setManualPriceAt(LocalDateTime.now());
        return toAssetResponse(assetRepository.save(asset));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public void deleteAsset(User user, UUID assetId) {
        InvestmentAsset asset = getOwnedAsset(user, assetId);
        operationRepository.bulkDeleteByAsset(asset);
        assetRepository.delete(asset);
    }

    @Transactional(readOnly = true)
    public List<InvestmentDto.AssetResponse> getAssets(User user) {
        return assetRepository.findByUserOrderByNameAsc(user).stream().map(this::toAssetResponse).toList();
    }

    @Transactional(readOnly = true)
    public InvestmentDto.AssetResponse getAsset(User user, UUID assetId) {
        return toAssetResponse(getOwnedAsset(user, assetId));
    }

    // ─── Operazioni ─────────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public InvestmentDto.OperationResponse addOperation(User user, UUID assetId, InvestmentDto.OperationRequest request) {
        InvestmentAsset asset = getOwnedAsset(user, assetId);
        InvestmentOperation operation = InvestmentOperation.builder().asset(asset).user(user).build();
        applyOperationRequest(operation, request);
        operationRepository.save(operation);
        validateHistory(asset);
        return toOperationResponse(operation);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public InvestmentDto.OperationResponse updateOperation(User user, UUID operationId, InvestmentDto.OperationRequest request) {
        InvestmentOperation operation = getOwnedOperation(user, operationId);
        applyOperationRequest(operation, request);
        operationRepository.save(operation);
        validateHistory(operation.getAsset());
        return toOperationResponse(operation);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE, allEntries = true)
    public void deleteOperation(User user, UUID operationId) {
        InvestmentOperation operation = getOwnedOperation(user, operationId);
        InvestmentAsset asset = operation.getAsset();
        operationRepository.delete(operation);
        validateHistory(asset);
    }

    /** Operazioni di un asset, dalla più recente. */
    @Transactional(readOnly = true)
    public List<InvestmentDto.OperationResponse> getOperations(User user, UUID assetId) {
        InvestmentAsset asset = getOwnedAsset(user, assetId);
        List<InvestmentOperation> operations =
                new ArrayList<>(operationRepository.findByAssetOrderByOperationDateAscCreatedAtAsc(asset));
        Collections.reverse(operations);
        return operations.stream().map(this::toOperationResponse).toList();
    }

    /** Operazioni di tutti gli asset in un periodo, dalla più recente. */
    @Transactional(readOnly = true)
    public List<InvestmentDto.OperationResponse> getOperationsInPeriod(User user, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("La data di fine precede quella di inizio");
        }
        List<InvestmentOperation> operations = new ArrayList<>(operationRepository.findByUserAndPeriod(user, start, end));
        Collections.reverse(operations);
        return operations.stream().map(this::toOperationResponse).toList();
    }

    // ─── Portafoglio ────────────────────────────────────────────────────────────

    // unless: un portafoglio incompleto (prezzo o cambio mancante) o con prezzi non aggiornati (provider giù) è
    // quasi sempre transitorio: cachandolo per tutto il TTL ritarderebbe il ritorno ai prezzi reali
    @Cacheable(value = CacheConfig.INVESTMENT_PORTFOLIO_CACHE,
            key = "#user.id + '_' + (#currency == null ? '' : #currency.toUpperCase())",
            unless = "#result == null || !#result.complete || !#result.positions.?[stale].isEmpty()")
    public InvestmentDto.PortfolioResponse getPortfolio(User user, String currency) {
        return computePortfolio(user, resolveCurrency(user, currency));
    }

    private InvestmentDto.PortfolioResponse computePortfolio(User user, String currency) {
        List<InvestmentAsset> assets = assetRepository.findByUserOrderByNameAsc(user);
        Map<UUID, List<InvestmentOperation>> operationsByAsset = operationRepository.findAllByUser(user).stream()
                .collect(Collectors.groupingBy(o -> o.getAsset().getId()));

        boolean complete = true;
        Map<UUID, Position> positions = new HashMap<>();
        for (InvestmentAsset asset : assets) {
            try {
                positions.put(asset.getId(), PositionCalculator.calculate(asset.getAssetType(),
                        operationsByAsset.getOrDefault(asset.getId(), List.of())));
            } catch (IllegalStateException e) {
                // Non dovrebbe succedere (le operazioni sono validate in scrittura): un asset incoerente non blocca il resto
                logger.error("Operazioni incoerenti per l'asset {}: {}", asset.getId(), e.getMessage());
                complete = false;
            }
        }

        List<InvestmentAsset> open = assets.stream()
                .filter(a -> positions.containsKey(a.getId()) && positions.get(a.getId()).quantity().signum() > 0)
                .toList();
        Map<UUID, ResolvedPrice> prices = marketPriceService.resolveAll(open);

        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        List<InvestmentDto.PositionResponse> positionResponses = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalUnrealized = BigDecimal.ZERO;
        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();

        for (InvestmentAsset asset : assets) {
            Position pos = positions.get(asset.getId());
            if (pos == null) {
                continue;
            }
            boolean isOpen = pos.quantity().signum() > 0;
            Optional<BigDecimal> costRate = rate(rates, asset.getCurrency(), currency);

            BigDecimal costBasis = costRate.map(r -> money(pos.costBasis().multiply(r))).orElse(null);
            BigDecimal realized = costRate.map(r -> money(pos.realizedPl().multiply(r))).orElse(null);
            BigDecimal income = costRate.map(r -> money(pos.income().multiply(r))).orElse(null);
            if (costRate.isEmpty()) {
                complete = false;
            }

            ResolvedPrice price = isOpen ? prices.get(asset.getId()) : null;
            BigDecimal marketValue = null;
            String priceCurrency = price != null ? price.currency() : asset.getCurrency();
            if (!isOpen) {
                marketValue = BigDecimal.ZERO;
            } else if (price == null) {
                complete = false;
            } else {
                Optional<BigDecimal> priceRate = rate(rates, price.currency(), currency);
                if (priceRate.isPresent()) {
                    marketValue = money(PositionCalculator.marketValue(asset.getAssetType(), pos.quantity(), price.price())
                            .multiply(priceRate.get()));
                } else {
                    complete = false;
                }
            }

            BigDecimal unrealized = (marketValue != null && costBasis != null) ? marketValue.subtract(costBasis) : null;
            BigDecimal unrealizedPercent = percent(unrealized, costBasis);

            if (realized != null) {
                totalRealized = totalRealized.add(realized);
            }
            if (income != null) {
                totalIncome = totalIncome.add(income);
            }
            if (isOpen && marketValue != null) {
                totalValue = totalValue.add(marketValue);
                byType.merge(asset.getAssetType().name(), marketValue, BigDecimal::add);
                byCurrency.merge(priceCurrency, marketValue, BigDecimal::add);
                if (costBasis != null) {
                    totalCost = totalCost.add(costBasis);
                    totalUnrealized = totalUnrealized.add(unrealized);
                }
            }

            positionResponses.add(InvestmentDto.PositionResponse.builder()
                    .assetId(asset.getId())
                    .assetType(asset.getAssetType())
                    .name(asset.getName())
                    .isin(asset.getIsin())
                    .symbol(asset.getSymbol())
                    .currency(asset.getCurrency())
                    .quantity(pos.quantity())
                    .avgPrice(pos.avgPrice())
                    .costBasis(costBasis)
                    .price(price != null ? price.price() : null)
                    .priceCurrency(price != null ? price.currency() : null)
                    .priceSource(price != null ? price.source() : null)
                    .priceAsOf(price != null ? price.asOf() : null)
                    .stale(price != null && price.stale())
                    .marketValue(marketValue)
                    .unrealizedPl(unrealized)
                    .unrealizedPlPercent(unrealizedPercent)
                    .realizedPl(realized)
                    .income(income)
                    .couponRate(asset.getCouponRate())
                    .couponFrequency(asset.getCouponFrequency())
                    .maturityDate(asset.getMaturityDate())
                    .build());
        }

        positionResponses.sort(Comparator
                .comparing((InvestmentDto.PositionResponse p) -> p.getQuantity().signum() > 0 ? 0 : 1)
                .thenComparing(InvestmentDto.PositionResponse::getMarketValue,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InvestmentDto.PositionResponse::getName));

        return InvestmentDto.PortfolioResponse.builder()
                .currency(currency)
                .totalValue(totalValue)
                .totalCostBasis(totalCost)
                .unrealizedPl(totalUnrealized)
                .unrealizedPlPercent(percent(totalUnrealized, totalCost))
                .realizedPl(totalRealized)
                .income(totalIncome)
                .complete(complete)
                .positions(positionResponses)
                .allocationByType(allocation(byType, totalValue))
                .allocationByCurrency(allocation(byCurrency, totalValue))
                .build();
    }

    // ─── Performance e storico ──────────────────────────────────────────────────

    // Non @Transactional: computePortfolio può scrivere l'ultimo prezzo noto, e un errore in una transazione
    // read-only condivisa la segnerebbe rollback-only
    public InvestmentDto.PerformanceResponse getPerformance(User user, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("La data di fine precede quella di inizio");
        }
        String currency = resolveCurrency(user, null);
        LocalDate before = start.minusDays(1);
        Map<UUID, List<InvestmentOperation>> operationsByAsset = operationRepository.findAllByUser(user).stream()
                .collect(Collectors.groupingBy(o -> o.getAsset().getId()));

        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal divested = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        boolean anyOperationBefore = false;

        for (InvestmentAsset asset : assetRepository.findByUserOrderByNameAsc(user)) {
            List<InvestmentOperation> operations = operationsByAsset.getOrDefault(asset.getId(), List.of());
            if (operations.isEmpty()) {
                continue;
            }
            anyOperationBefore |= operations.stream().anyMatch(o -> !o.getOperationDate().isAfter(before));
            Position atEnd = PositionCalculator.calculate(asset.getAssetType(), operations, end);
            Position atStart = PositionCalculator.calculate(asset.getAssetType(), operations, before);
            BigDecimal rate = rate(rates, asset.getCurrency(), currency).orElseThrow(() -> new IllegalStateException(
                    "Tasso di cambio " + asset.getCurrency() + "→" + currency + " non disponibile"));
            invested = invested.add(atEnd.totalInvested().subtract(atStart.totalInvested()).multiply(rate));
            divested = divested.add(atEnd.totalProceeds().subtract(atStart.totalProceeds()).multiply(rate));
            realized = realized.add(atEnd.realizedPl().subtract(atStart.realizedPl()).multiply(rate));
            income = income.add(atEnd.income().subtract(atStart.income()).multiply(rate));
        }

        BigDecimal startValue = snapshotValue(user, before, currency, rates);
        if (startValue == null && !anyOperationBefore) {
            startValue = BigDecimal.ZERO;
        }
        BigDecimal endValue;
        if (!end.isBefore(LocalDate.now())) {
            InvestmentDto.PortfolioResponse current = computePortfolio(user, currency);
            endValue = current.isComplete() ? current.getTotalValue() : null;
        } else {
            endValue = snapshotValue(user, end, currency, rates);
        }

        BigDecimal gain = (startValue != null && endValue != null)
                ? money(endValue.subtract(startValue).subtract(invested.subtract(divested)).add(income))
                : null;
        return InvestmentDto.PerformanceResponse.builder()
                .startDate(start)
                .endDate(end)
                .currency(currency)
                .invested(money(invested))
                .divested(money(divested))
                .realizedPl(money(realized))
                .income(money(income))
                .startValue(startValue)
                .endValue(endValue)
                .totalGain(gain)
                .build();
    }

    @Transactional(readOnly = true)
    public InvestmentDto.HistoryResponse getHistory(User user, int months, String currency) {
        int safeMonths = Math.max(1, Math.min(months, MAX_HISTORY_MONTHS));
        String target = resolveCurrency(user, currency);
        LocalDate today = LocalDate.now();
        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        List<InvestmentDto.HistoryPoint> points = new ArrayList<>();
        for (InvestmentPortfolioSnapshot s : snapshotRepository
                .findByUserAndSnapshotDateBetweenOrderBySnapshotDateAsc(user, today.minusMonths(safeMonths), today)) {
            Optional<BigDecimal> rate = rate(rates, s.getCurrency(), target);
            if (rate.isEmpty()) {
                continue;
            }
            points.add(InvestmentDto.HistoryPoint.builder()
                    .date(s.getSnapshotDate())
                    .marketValue(money(s.getMarketValue().multiply(rate.get())))
                    .costBasis(money(s.getCostBasis().multiply(rate.get())))
                    .build());
        }
        return InvestmentDto.HistoryResponse.builder().currency(target).points(points).build();
    }

    /** Valore di portafoglio alla data da snapshot (entro {@link #SNAPSHOT_MAX_AGE_DAYS} giorni), convertito. */
    private BigDecimal snapshotValue(User user, LocalDate date, String currency, Map<String, Optional<BigDecimal>> rates) {
        return snapshotRepository.findFirstByUserAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(user, date)
                .filter(s -> !s.getSnapshotDate().isBefore(date.minusDays(SNAPSHOT_MAX_AGE_DAYS)))
                .flatMap(s -> rate(rates, s.getCurrency(), currency).map(r -> money(s.getMarketValue().multiply(r))))
                .orElse(null);
    }

    // ─── Snapshot giornaliero ───────────────────────────────────────────────────

    /** Salva il valore del portafoglio di ogni utente con asset: serve a ricostruire lo storico, non ricalcolabile dai soli prezzi di oggi. */
    @Scheduled(cron = "0 30 23 * * ?")
    public void takeDailySnapshots() {
        LocalDate today = LocalDate.now();
        List<User> users = assetRepository.findUsersWithAssets();
        logger.info("[InvestmentSnapshot] Utenti con investimenti: {}", users.size());
        for (User user : users) {
            try {
                takeSnapshot(user, today);
            } catch (Exception e) {
                // Un utente che fallisce (o un secondo pod che ha già scritto lo snapshot) non blocca gli altri
                logger.warn("[InvestmentSnapshot] Snapshot dell'utente {} non salvato: {}", user.getId(), e.getMessage());
            }
        }
    }

    void takeSnapshot(User user, LocalDate date) {
        String currency = resolveCurrency(user, null);
        // Calcolo fuori dalla transazione: chiama provider HTTP
        InvestmentDto.PortfolioResponse portfolio = computePortfolio(user, currency);
        if (!portfolio.isComplete()) {
            logger.warn("[InvestmentSnapshot] Portafoglio dell'utente {} incompleto: snapshot saltato", user.getId());
            return;
        }
        boolean hasOpenPositions = portfolio.getPositions().stream().anyMatch(p -> p.getQuantity().signum() > 0);
        if (!hasOpenPositions) {
            // Dopo una liquidazione si registra lo zero una volta, per non lasciare lo storico fermo all'ultimo valore
            boolean hadValue = snapshotRepository
                    .findFirstByUserAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(user, date)
                    .map(s -> s.getMarketValue().signum() > 0)
                    .orElse(false);
            if (!hadValue) {
                return;
            }
        }
        transactionTemplate.executeWithoutResult(status -> {
            InvestmentPortfolioSnapshot snapshot = snapshotRepository.findByUserAndSnapshotDate(user, date)
                    .orElseGet(() -> InvestmentPortfolioSnapshot.builder().user(user).snapshotDate(date).build());
            snapshot.setMarketValue(portfolio.getTotalValue());
            snapshot.setCostBasis(portfolio.getTotalCostBasis());
            snapshot.setCurrency(currency);
            snapshotRepository.save(snapshot);
        });
    }

    // ─── Helper ─────────────────────────────────────────────────────────────────

    private InvestmentAsset getOwnedAsset(User user, UUID assetId) {
        return assetRepository.findByIdAndUser(assetId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset non trovato"));
    }

    private InvestmentOperation getOwnedOperation(User user, UUID operationId) {
        return operationRepository.findByIdAndUser(operationId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Operazione non trovata"));
    }

    private void checkUnique(User user, UUID selfId, String isin, String symbol) {
        List<InvestmentAsset> others = assetRepository.findByUserOrderByNameAsc(user).stream()
                .filter(a -> !a.getId().equals(selfId))
                .toList();
        if (isin != null && others.stream().anyMatch(a -> isin.equals(a.getIsin()))) {
            throw new IllegalStateException("Hai già un asset con ISIN " + isin);
        }
        if (symbol != null && others.stream().anyMatch(a -> symbol.equals(a.getSymbol()))) {
            throw new IllegalStateException("Hai già un asset con simbolo " + symbol);
        }
    }

    private void applyBondFields(InvestmentAsset asset, InvestmentAssetType type, BigDecimal couponRate,
                                 CouponFrequency frequency, LocalDate maturity) {
        boolean bond = type == InvestmentAssetType.BOND;
        asset.setCouponRate(bond ? couponRate : null);
        asset.setCouponFrequency(bond ? frequency : null);
        asset.setMaturityDate(bond ? maturity : null);
    }

    private void applyOperationRequest(InvestmentOperation operation, InvestmentDto.OperationRequest request) {
        if (request.getOperationDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("La data dell'operazione non può essere nel futuro");
        }
        InvestmentOperationType type = request.getType();
        if (type == InvestmentOperationType.BUY || type == InvestmentOperationType.SELL) {
            if (request.getQuantity() == null || request.getPrice() == null) {
                throw new IllegalArgumentException("Quantità e prezzo sono obbligatori per acquisti e vendite");
            }
            operation.setQuantity(request.getQuantity());
            operation.setPrice(request.getPrice());
            operation.setAmount(null);
        } else {
            if (request.getAmount() == null) {
                throw new IllegalArgumentException("L'importo è obbligatorio per dividendi e cedole");
            }
            operation.setAmount(request.getAmount());
            operation.setQuantity(null);
            operation.setPrice(null);
        }
        operation.setType(type);
        operation.setOperationDate(request.getOperationDate());
        operation.setFees(request.getFees() != null ? request.getFees() : BigDecimal.ZERO);
        operation.setNotes(request.getNotes());
    }

    /** Rigioca lo storico dell'asset: una vendita che supera la quantità detenuta lancia IllegalStateException (409) e annulla la modifica. */
    private void validateHistory(InvestmentAsset asset) {
        PositionCalculator.calculate(asset.getAssetType(),
                operationRepository.findByAssetOrderByOperationDateAscCreatedAtAsc(asset));
    }

    private String resolveCurrency(User user, String currency) {
        if (currency != null && !currency.isBlank()) {
            return currency.trim().toUpperCase();
        }
        return user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";
    }

    private Optional<BigDecimal> rate(Map<String, Optional<BigDecimal>> cache, String from, String to) {
        return cache.computeIfAbsent(from.toUpperCase(), f -> currencyConversionService.getRate(f, to));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static List<InvestmentDto.AllocationItem> allocation(Map<String, BigDecimal> values, BigDecimal total) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> InvestmentDto.AllocationItem.builder()
                        .key(e.getKey())
                        .value(e.getValue())
                        .percent(percent(e.getValue(), total))
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String normalizeIsin(String isin) {
        return isin == null || isin.isBlank() ? null : isin.trim().toUpperCase();
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
    }

    private InvestmentDto.AssetResponse toAssetResponse(InvestmentAsset a) {
        return InvestmentDto.AssetResponse.builder()
                .id(a.getId())
                .assetType(a.getAssetType())
                .name(a.getName())
                .isin(a.getIsin())
                .symbol(a.getSymbol())
                .currency(a.getCurrency())
                .priceSource(a.getPriceSource())
                .manualPrice(a.getManualPrice())
                .manualPriceAt(a.getManualPriceAt())
                .couponRate(a.getCouponRate())
                .couponFrequency(a.getCouponFrequency())
                .maturityDate(a.getMaturityDate())
                .createdAt(a.getCreatedAt())
                .build();
    }

    private InvestmentDto.OperationResponse toOperationResponse(InvestmentOperation o) {
        return InvestmentDto.OperationResponse.builder()
                .id(o.getId())
                .assetId(o.getAsset().getId())
                .assetName(o.getAsset().getName())
                .type(o.getType())
                .operationDate(o.getOperationDate())
                .quantity(o.getQuantity())
                .price(o.getPrice())
                .amount(o.getAmount())
                .fees(o.getFees())
                .notes(o.getNotes())
                .build();
    }
}
