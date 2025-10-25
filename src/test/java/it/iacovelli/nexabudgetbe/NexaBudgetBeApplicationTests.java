package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.dto.AuthDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
class NexaBudgetBeApplicationTests {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final String TEST_USERNAME = "utente_test";
    private final String TEST_EMAIL = "utente_test@example.com";
    private final String TEST_PASSWORD = "Password123!";

    private User savedUser;

    @BeforeEach
    void setUp() {
        // Elimina l'utente se già esiste
        userRepository.findByUsername(TEST_USERNAME)
                .ifPresent(user -> userRepository.delete(user));

        // Registra un nuovo utente per il test
        UserDto.UserRequest registerRequest = new UserDto.UserRequest();
        registerRequest.setUsername(TEST_USERNAME);
        registerRequest.setEmail(TEST_EMAIL);
        registerRequest.setPassword(TEST_PASSWORD);

        AuthDto.AuthResponse response = authService.register(registerRequest);
        assertNotNull(response.getToken(), "Il token di registrazione non dovrebbe essere nullo");

        // Recupera l'utente dal database
        Optional<User> userOpt = userRepository.findByUsername(TEST_USERNAME);
        assertTrue(userOpt.isPresent(), "L'utente dovrebbe essere presente nel database dopo la registrazione");

        savedUser = userOpt.get();
        System.out.println("Utente creato - ID: " + savedUser.getId() +
                ", Username: " + savedUser.getUsername() +
                ", Password Hash: " + savedUser.getPasswordHash());
    }

    @AfterEach
    void tearDown() {
        // Pulizia dopo i test
        userRepository.findByUsername(TEST_USERNAME)
                .ifPresent(user -> userRepository.delete(user));
    }


    @Test
    void testRegisterAndLogin() {
        // Verifica diretta che la password sia stata salvata correttamente
        boolean passwordMatches = passwordEncoder.matches(TEST_PASSWORD, savedUser.getPasswordHash());
        System.out.println("La password corrisponde all'hash memorizzato? " + passwordMatches);
        assertTrue(passwordMatches, "La password dovrebbe corrispondere all'hash memorizzato");

        // Prova a fare login con le credenziali appena create
        AuthDto.LoginRequest loginRequest = new AuthDto.LoginRequest();
        loginRequest.setUsername(TEST_USERNAME);
        loginRequest.setPassword(TEST_PASSWORD);

        try {
            AuthDto.AuthResponse loginResponse = authService.login(loginRequest);
            assertNotNull(loginResponse, "La risposta di login non dovrebbe essere nulla");
            assertNotNull(loginResponse.getToken(), "Il token di login non dovrebbe essere nullo");
            System.out.println("Login riuscito - Token: " + loginResponse.getToken());
        } catch (Exception e) {
            fail("Il login ha fallito con l'eccezione: " + e.getMessage());
        }
    }

    @Test
    void testDirectPasswordEncoding() {
        // Test diretto dell'encoder con la password reale
        String encodedPassword = passwordEncoder.encode(TEST_PASSWORD);

        System.out.println("Password originale: " + TEST_PASSWORD);
        System.out.println("Password codificata: " + encodedPassword);
        System.out.println("Password hash salvata: " + savedUser.getPasswordHash());

        // Codifica la password più volte per vedere se gli hash sono diversi
        System.out.println("Hash aggiuntivo 1: " + passwordEncoder.encode(TEST_PASSWORD));
        System.out.println("Hash aggiuntivo 2: " + passwordEncoder.encode(TEST_PASSWORD));

        // Verifica che l'encoder riconosca i propri hash
        boolean selfMatch = passwordEncoder.matches(TEST_PASSWORD, encodedPassword);
        System.out.println("L'encoder riconosce il proprio hash: " + selfMatch);

        boolean matches = passwordEncoder.matches(TEST_PASSWORD, savedUser.getPasswordHash());
        System.out.println("Password corrisponde all'hash salvato: " + matches);

        assertTrue(matches, "La password dovrebbe corrispondere all'hash salvato nel database");
    }

    @Test
    void debugPasswordEncoder() {
        // 1. Crea un utente direttamente con l'encoder iniettato nel test
        String rawPassword = TEST_PASSWORD;
        String encodedPassword = passwordEncoder.encode(rawPassword);

        User user = User.builder()
                .username("debug_user")
                .email("debug@example.com")
                .passwordHash(encodedPassword)
                .build();

        User savedUser = userRepository.save(user);

        // 2. Verifica l'uguaglianza degli oggetti encoder
        System.out.println("Il passwordEncoder usato nel test è: " + passwordEncoder.getClass().getName());
        System.out.println("Hash generato dal test encoder: " + encodedPassword);

        // 3. Prova ad effettuare login con il servizio di autenticazione
        AuthDto.LoginRequest loginRequest = new AuthDto.LoginRequest();
        loginRequest.setUsername("debug_user");
        loginRequest.setPassword(rawPassword);

        try {
            AuthDto.AuthResponse response = authService.login(loginRequest);
            System.out.println("Login riuscito: " + (response != null));
        } catch (Exception e) {
            System.out.println("Login fallito: " + e.getMessage());
            e.printStackTrace();
        }

        // 4. Pulisci il database
        userRepository.delete(savedUser);
    }


}
