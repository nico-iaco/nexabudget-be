package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.InvestmentAsset;
import it.iacovelli.nexabudgetbe.model.InvestmentOperation;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentOperationRepository extends JpaRepository<InvestmentOperation, UUID> {

    // Ordine cronologico stabile: a parità di data, la creazione decide (un BUY e un SELL dello stesso giorno)
    List<InvestmentOperation> findByAssetOrderByOperationDateAscCreatedAtAsc(InvestmentAsset asset);

    @Query("SELECT o FROM InvestmentOperation o JOIN FETCH o.asset WHERE o.user = :user ORDER BY o.operationDate ASC, o.createdAt ASC")
    List<InvestmentOperation> findAllByUser(@Param("user") User user);

    @Query("SELECT o FROM InvestmentOperation o JOIN FETCH o.asset WHERE o.user = :user "
            + "AND o.operationDate BETWEEN :start AND :end ORDER BY o.operationDate ASC, o.createdAt ASC")
    List<InvestmentOperation> findByUserAndPeriod(@Param("user") User user,
                                                  @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT o FROM InvestmentOperation o JOIN FETCH o.asset WHERE o.id = :id AND o.user = :user")
    Optional<InvestmentOperation> findByIdAndUser(@Param("id") UUID id, @Param("user") User user);

    // In produzione le operazioni spariscono con ON DELETE CASCADE; lo schema generato dai test non lo ha
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM InvestmentOperation o WHERE o.asset = :asset")
    int bulkDeleteByAsset(@Param("asset") InvestmentAsset asset);
}
