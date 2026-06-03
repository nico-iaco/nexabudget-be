package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.ApiKey;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);

    @Query("SELECT ak FROM ApiKey ak JOIN FETCH ak.user WHERE ak.keyHash = :keyHash")
    Optional<ApiKey> findByKeyHashWithUser(@Param("keyHash") String keyHash);
    List<ApiKey> findByUser(User user);
    Optional<ApiKey> findByIdAndUser(UUID id, User user);
}
