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
@Table(name = "investment_assets")
public class InvestmentAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 16)
    private InvestmentAssetType assetType;

    @Column(nullable = false)
    private String name;

    @Column(length = 12)
    private String isin;

    // Ticker nella notazione del provider (Yahoo: es. "VWCE.DE", "ENEL.MI")
    @Column(length = 32)
    private String symbol;

    // Valuta in cui il prezzo dell'asset è espresso
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 16)
    private PriceSource priceSource;

    @Column(name = "manual_price", precision = 19, scale = 6)
    private BigDecimal manualPrice;

    @Column(name = "manual_price_at")
    private LocalDateTime manualPriceAt;

    // Ultimo prezzo live noto: fallback quando il provider non risponde
    @Column(name = "last_price", precision = 19, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "last_price_currency", length = 3)
    private String lastPriceCurrency;

    @Column(name = "last_price_at")
    private LocalDateTime lastPriceAt;

    // Solo obbligazioni
    @Column(name = "coupon_rate", precision = 9, scale = 6)
    private BigDecimal couponRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_frequency", length = 16)
    private CouponFrequency couponFrequency;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
