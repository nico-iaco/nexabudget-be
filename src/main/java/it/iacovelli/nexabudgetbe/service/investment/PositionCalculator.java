package it.iacovelli.nexabudgetbe.service.investment;

import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.InvestmentOperation;
import it.iacovelli.nexabudgetbe.model.InvestmentOperationType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Calcolo della posizione di un asset a partire dalle sue operazioni, con il metodo del costo medio ponderato.
 * Tutti gli importi sono nella valuta dell'asset. Per le obbligazioni (quotate in % del nominale) quantity è il
 * valore nominale e price la quotazione in %: il valore è quantity × price × 0,01.
 */
public final class PositionCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private PositionCalculator() {
    }

    /**
     * @param quantity       quantità (o nominale) detenuta
     * @param costBasis      costo di carico della quantità detenuta, commissioni di acquisto incluse
     * @param avgPrice       prezzo medio di carico per unità di prezzo (obbligazioni: % del nominale); zero se quantity = 0
     * @param realizedPl     profitto/perdita realizzato dalle vendite, commissioni incluse
     * @param income         dividendi e cedole incassati
     * @param totalInvested  somma degli esborsi per acquisti (commissioni incluse)
     * @param totalProceeds  somma degli incassi dalle vendite (al netto delle commissioni)
     */
    public record Position(BigDecimal quantity, BigDecimal costBasis, BigDecimal avgPrice, BigDecimal realizedPl,
                           BigDecimal income, BigDecimal totalInvested, BigDecimal totalProceeds) {
    }

    /**
     * @param operations operazioni dell'asset in ordine cronologico
     * @throws IllegalStateException se una vendita supera la quantità detenuta in quel momento
     */
    public static Position calculate(InvestmentAssetType type, List<InvestmentOperation> operations) {
        return calculate(type, operations, null);
    }

    /**
     * Come {@link #calculate(InvestmentAssetType, List)} considerando solo le operazioni fino a {@code asOf}
     * incluso (null = tutte).
     */
    public static Position calculate(InvestmentAssetType type, List<InvestmentOperation> operations, LocalDate asOf) {
        BigDecimal multiplier = type.priceMultiplier();
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal proceeds = BigDecimal.ZERO;

        // Nello stesso giorno gli acquisti precedono le vendite: l'ordine di inserimento non deve decidere se una vendita è valida.
        // Il sort è stabile, quindi a parità l'ordine ricevuto (creazione) resta.
        List<InvestmentOperation> ordered = operations.stream()
                .sorted(Comparator.comparing(InvestmentOperation::getOperationDate)
                        .thenComparingInt(op -> op.getType() == InvestmentOperationType.BUY ? 0
                                : op.getType() == InvestmentOperationType.SELL ? 2 : 1))
                .toList();

        for (InvestmentOperation op : ordered) {
            if (asOf != null && op.getOperationDate().isAfter(asOf)) {
                continue;
            }
            BigDecimal fees = op.getFees() != null ? op.getFees() : BigDecimal.ZERO;
            InvestmentOperationType opType = op.getType();
            switch (opType) {
                case BUY -> {
                    BigDecimal outlay = op.getQuantity().multiply(op.getPrice()).multiply(multiplier).add(fees);
                    quantity = quantity.add(op.getQuantity());
                    costBasis = costBasis.add(outlay);
                    invested = invested.add(outlay);
                }
                case SELL -> {
                    if (op.getQuantity().compareTo(quantity) > 0) {
                        throw new IllegalStateException("Vendita del " + op.getOperationDate() + " di "
                                + op.getQuantity().stripTrailingZeros().toPlainString()
                                + " superiore alla quantità detenuta ("
                                + quantity.stripTrailingZeros().toPlainString() + ")");
                    }
                    BigDecimal gross = op.getQuantity().multiply(op.getPrice()).multiply(multiplier);
                    BigDecimal net = gross.subtract(fees);
                    // Costo della quota venduta: tutto il costo residuo se si chiude la posizione (evita residui di arrotondamento)
                    BigDecimal soldCost = op.getQuantity().compareTo(quantity) == 0
                            ? costBasis
                            : costBasis.multiply(op.getQuantity()).divide(quantity, MC);
                    realized = realized.add(net.subtract(soldCost));
                    costBasis = costBasis.subtract(soldCost);
                    quantity = quantity.subtract(op.getQuantity());
                    proceeds = proceeds.add(net);
                }
                case DIVIDEND, COUPON -> income = income.add(op.getAmount() != null ? op.getAmount() : BigDecimal.ZERO);
            }
        }

        BigDecimal avgPrice = quantity.signum() == 0
                ? BigDecimal.ZERO
                : costBasis.divide(quantity, MC).divide(multiplier, MC).setScale(6, RoundingMode.HALF_UP);
        return new Position(quantity, costBasis, avgPrice, realized, income, invested, proceeds);
    }

    /** Valore di mercato della posizione al prezzo indicato (null se il prezzo non è noto). */
    public static BigDecimal marketValue(InvestmentAssetType type, BigDecimal quantity, BigDecimal price) {
        if (price == null) {
            return null;
        }
        return quantity.multiply(price).multiply(type.priceMultiplier());
    }
}
