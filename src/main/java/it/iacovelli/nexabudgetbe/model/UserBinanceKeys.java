package it.iacovelli.nexabudgetbe.model;

import it.iacovelli.nexabudgetbe.config.CryptoConverter;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_binance_keys")
public class UserBinanceKeys {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "api_key", nullable = false)
    @Convert(converter = CryptoConverter.class)
    private String apiKey;

    @Column(name = "api_secret", nullable = false)
    @Convert(converter = CryptoConverter.class)
    private String apiSecret;
}
