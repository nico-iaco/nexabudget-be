package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.ApiKey;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findByUser(User user);
    Optional<ApiKey> findByIdAndUser(UUID id, User user);
}
