package it.iacovelli.nexabudgetbe.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "investment_portfolio_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_investment_snapshot_user_date", columnNames = {"user_id", "snapshot_date"}))
public class InvestmentPortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "market_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal marketValue;

    @Column(name = "cost_basis", nullable = false, precision = 19, scale = 4)
    private BigDecimal costBasis;

    @Column(nullable = false, length = 3)
    private String currency;
}
