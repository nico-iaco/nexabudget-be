package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User createUser(User user) {
        logger.info("Tentativo di creazione utente: {}", user.getUsername());
        
        if (userRepository.existsByUsername(user.getUsername())) {
            logger.warn("Tentativo di creazione utente con username già esistente: {}", user.getUsername());
            throw new IllegalArgumentException("Username già in uso");
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            logger.warn("Tentativo di creazione utente con email già esistente: {}", user.getEmail());
            throw new IllegalArgumentException("Email già in uso");
        }

        user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        User savedUser = userRepository.save(user);
        logger.info("Utente creato con successo: {} (ID: {})", savedUser.getUsername(), savedUser.getId());
        return savedUser;
    }

    public Optional<User> getUserById(UUID id) {
        logger.debug("Recupero utente per ID: {}", id);
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        logger.debug("Recupero utente per username: {}", username);
        return userRepository.findByUsername(username);
    }

    public Optional<User> getUserByEmail(String email) {
        logger.debug("Recupero utente per email: {}", email);
        return userRepository.findByEmail(email);
    }

    public List<User> getAllUsers() {
        logger.debug("Recupero lista completa utenti");
        return userRepository.findAll();
    }

    public User updateUser(User user) {
        logger.info("Aggiornamento utente: {} (ID: {})", user.getUsername(), user.getId());
        return userRepository.save(user);
    }

    public void deleteUser(UUID userId) {
        logger.info("Eliminazione utente con ID: {}", userId);
        userRepository.deleteById(userId);
    }

    public boolean verifyPassword(User user, String rawPassword) {
        logger.debug("Verifica password per utente: {}", user.getUsername());
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }
}
