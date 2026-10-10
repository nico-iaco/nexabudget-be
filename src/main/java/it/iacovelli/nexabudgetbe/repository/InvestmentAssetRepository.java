package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.InvestmentAsset;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentAssetRepository extends JpaRepository<InvestmentAsset, UUID> {

    List<InvestmentAsset> findByUserOrderByNameAsc(User user);

    Optional<InvestmentAsset> findByIdAndUser(UUID id, User user);

    @Query("SELECT DISTINCT a.user FROM InvestmentAsset a")
    List<User> findUsersWithAssets();

    // Aggiornamento mirato: il prezzo live viene scritto mentre il portafoglio è in lettura, salvare l'entità
    // caricata sovrascriverebbe eventuali modifiche concorrenti (rinomina, prezzo manuale)
    // REQUIRES_NEW: è solo un fallback, un suo errore non deve segnare rollback-only la transazione del chiamante
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InvestmentAsset a SET a.lastPrice = :price, a.lastPriceCurrency = :currency, a.lastPriceAt = :at WHERE a.id = :id")
    int updateLastPrice(@Param("id") UUID id, @Param("price") BigDecimal price,
                        @Param("currency") String currency, @Param("at") LocalDateTime at);
}
