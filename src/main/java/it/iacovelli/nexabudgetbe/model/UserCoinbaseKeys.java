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
@Table(name = "user_coinbase_keys")
public class UserCoinbaseKeys {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "api_key_name", nullable = false)
    @Convert(converter = CryptoConverter.class)
    private String apiKeyName;

    @Column(name = "private_key", nullable = false, length = 4096)
    @Convert(converter = CryptoConverter.class)
    private String privateKey;
}
