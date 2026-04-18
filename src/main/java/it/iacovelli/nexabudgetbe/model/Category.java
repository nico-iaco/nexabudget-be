package it.iacovelli.nexabudgetbe.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "categories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_category_user_name_type", columnNames = {"user_id", "name", "transaction_type"})
})
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Se nullo, è una categoria di default
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private TransactionType transactionType; // Applica a ENTRATA o USCITA

}
