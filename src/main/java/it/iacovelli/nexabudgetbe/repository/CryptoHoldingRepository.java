package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.CryptoHolding;
import it.iacovelli.nexabudgetbe.model.HoldingSource;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CryptoHoldingRepository extends JpaRepository<CryptoHolding, UUID> {
    List<CryptoHolding> findByUser(User user);

    Optional<CryptoHolding> findByUserAndSymbolAndSource(User user, String symbol, HoldingSource source);

    void deleteByUserAndSource(User user, HoldingSource source);

    // DELETE in un solo statement (la derivata deleteByUserAndSource carica e cancella riga per riga)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CryptoHolding h WHERE h.user = :user AND h.source = :source")
    int bulkDeleteByUserAndSource(@Param("user") User user, @Param("source") HoldingSource source);
}
