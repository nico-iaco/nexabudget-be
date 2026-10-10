package it.iacovelli.nexabudgetbe.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "investment_operations", indexes = {
        @Index(name = "idx_investment_operations_asset_date", columnList = "asset_id, operation_date"),
        @Index(name = "idx_investment_operations_user_date", columnList = "user_id, operation_date")
})
public class InvestmentOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    @ToString.Exclude
    private InvestmentAsset asset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvestmentOperationType type;

    @Column(name = "operation_date", nullable = false)
    private LocalDate operationDate;

    // Quote (azioni/ETF) o valore nominale (obbligazioni); null per DIVIDEND/COUPON
    @Column(precision = 28, scale = 10)
    private BigDecimal quantity;

    // Prezzo unitario (obbligazioni: % del nominale), nella valuta dell'asset; null per DIVIDEND/COUPON
    @Column(precision = 19, scale = 6)
    private BigDecimal price;

    // Incasso netto per DIVIDEND/COUPON, nella valuta dell'asset
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fees = BigDecimal.ZERO;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
