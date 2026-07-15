package it.iacovelli.nexabudgetbe.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@SQLRestriction("deleted = false")
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private String currency;

    @Column(name = "requisition_id")
    private String requisitionId;

    @Column(name = "external_account_id")
    private String externalAccountId;

    /**
     * Provider di aggregazione bancaria collegato a questo conto (GoCardless, Enable Banking, ...).
     * Null per i conti gestiti manualmente (mai collegati a un provider esterno).
     * {@code requisitionId} ospita l'identificativo di sessione/requisition del provider,
     * {@code externalAccountId} l'id del conto lato provider — entrambi generici e riusati da ogni provider.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private BankProvider provider;

    @Column(name = "last_external_sync")
    private LocalDateTime lastExternalSync;

    @Column(name = "is_synchronizing")
    private Boolean isSynchronizing;

    @Column(name = "requires_reauth")
    private Boolean requiresReauth;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (isSynchronizing == null) {
            isSynchronizing = false;
        }
        if (requiresReauth == null) {
            requiresReauth = false;
        }
        if (deleted == null) deleted = false;
    }
}
