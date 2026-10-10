package it.iacovelli.nexabudgetbe.service.investment;

import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.InvestmentOperation;
import it.iacovelli.nexabudgetbe.model.InvestmentOperationType;
import it.iacovelli.nexabudgetbe.service.investment.PositionCalculator.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionCalculatorTest {

    private static InvestmentOperation buy(String date, String qty, String price, String fees) {
        return op(InvestmentOperationType.BUY, date, qty, price, null, fees);
    }

    private static InvestmentOperation sell(String date, String qty, String price, String fees) {
        return op(InvestmentOperationType.SELL, date, qty, price, null, fees);
    }

    private static InvestmentOperation income(InvestmentOperationType type, String date, String amount) {
        return op(type, date, null, null, amount, "0");
    }

    private static InvestmentOperation op(InvestmentOperationType type, String date, String qty, String price,
                                          String amount, String fees) {
        return InvestmentOperation.builder()
                .type(type)
                .operationDate(LocalDate.parse(date))
                .quantity(qty != null ? new BigDecimal(qty) : null)
                .price(price != null ? new BigDecimal(price) : null)
                .amount(amount != null ? new BigDecimal(amount) : null)
                .fees(new BigDecimal(fees))
                .build();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "atteso " + expected + " ma era " + actual);
    }

    @Test
    void weightedAverageCost_partialSellAndFullClose() {
        List<InvestmentOperation> ops = List.of(
                buy("2026-01-10", "10", "100", "5"),
                sell("2026-02-10", "4", "120", "2"),
                sell("2026-03-10", "6", "110", "0"));

        Position afterPartial = PositionCalculator.calculate(InvestmentAssetType.ETF, ops.subList(0, 2));
        assertDecimal("6", afterPartial.quantity());
        // costo 1005 (commissioni incluse): vendendo 4 su 10 si scarica il 40% = 402
        assertDecimal("603", afterPartial.costBasis());
        assertDecimal("100.5", afterPartial.avgPrice());
        // incasso netto 480 - 2 = 478, meno il costo scaricato 402
        assertDecimal("76", afterPartial.realizedPl());

        Position closed = PositionCalculator.calculate(InvestmentAssetType.ETF, ops);
        assertDecimal("0", closed.quantity());
        assertDecimal("0", closed.costBasis());
        assertDecimal("0", closed.avgPrice());
        // chiusura: 660 - 603 = 57, che si somma ai 76
        assertDecimal("133", closed.realizedPl());
        assertDecimal("1005", closed.totalInvested());
        assertDecimal("1138", closed.totalProceeds());
    }

    @Test
    void dividendsAndCouponsAreIncome_notCost() {
        Position p = PositionCalculator.calculate(InvestmentAssetType.STOCK, List.of(
                buy("2026-01-10", "5", "20", "0"),
                income(InvestmentOperationType.DIVIDEND, "2026-04-01", "12.5"),
                income(InvestmentOperationType.DIVIDEND, "2026-07-01", "7.5")));
        assertDecimal("100", p.costBasis());
        assertDecimal("20", p.income());
        assertDecimal("0", p.realizedPl());
    }

    @Test
    void bond_priceIsPercentOfNominal() {
        List<InvestmentOperation> ops = List.of(
                buy("2026-01-10", "10000", "98.5", "0"),
                sell("2026-06-10", "5000", "101", "0"));
        Position p = PositionCalculator.calculate(InvestmentAssetType.BOND, ops);

        assertDecimal("5000", p.quantity());
        assertDecimal("4925", p.costBasis());
        assertDecimal("98.5", p.avgPrice());
        // 5000 × 101% = 5050 incassati contro 4925 di costo
        assertDecimal("125", p.realizedPl());
        assertDecimal("5000", PositionCalculator.marketValue(InvestmentAssetType.BOND, p.quantity(), new BigDecimal("100")));
    }

    @Test
    void sellBeyondHeldQuantity_throws() {
        List<InvestmentOperation> ops = List.of(buy("2026-01-10", "5", "10", "0"), sell("2026-02-10", "6", "10", "0"));
        assertThrows(IllegalStateException.class, () -> PositionCalculator.calculate(InvestmentAssetType.ETF, ops));
    }

    @Test
    void sellBeforeFirstBuy_throws() {
        List<InvestmentOperation> ops = List.of(sell("2026-01-05", "1", "10", "0"), buy("2026-01-10", "5", "10", "0"));
        assertThrows(IllegalStateException.class, () -> PositionCalculator.calculate(InvestmentAssetType.ETF, ops));
    }

    @Test
    void sameDay_buyIsProcessedBeforeSell_regardlessOfInsertionOrder() {
        // La vendita è stata inserita per prima ma è dello stesso giorno dell'acquisto: valida
        List<InvestmentOperation> ops = List.of(sell("2026-01-10", "5", "12", "0"), buy("2026-01-10", "5", "10", "0"));
        Position p = PositionCalculator.calculate(InvestmentAssetType.ETF, ops);
        assertDecimal("0", p.quantity());
        assertDecimal("10", p.realizedPl());
    }

    @Test
    void asOf_ignoresLaterOperations() {
        List<InvestmentOperation> ops = List.of(
                buy("2026-01-10", "10", "100", "0"),
                sell("2026-03-10", "10", "150", "0"));
        Position atFeb = PositionCalculator.calculate(InvestmentAssetType.ETF, ops, LocalDate.parse("2026-02-28"));
        assertDecimal("10", atFeb.quantity());
        assertDecimal("0", atFeb.realizedPl());
        Position atEnd = PositionCalculator.calculate(InvestmentAssetType.ETF, ops, LocalDate.parse("2026-03-10"));
        assertDecimal("500", atEnd.realizedPl());
    }

    @Test
    void marketValue_nullPrice_isNull() {
        assertEquals(null, PositionCalculator.marketValue(InvestmentAssetType.ETF, BigDecimal.TEN, null));
    }
}
