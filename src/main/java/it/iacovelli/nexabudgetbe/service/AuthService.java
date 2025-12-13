package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AuthDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final JwtTokenProvider jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtTokenProvider jwtUtil, AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
        logger.info("AuthService inizializzato con encoder: {}", passwordEncoder.getClass().getName());
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest loginRequest) {
        logger.info("Tentativo di login per l'utente: {}", loginRequest.getUsername());
        
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(),
                            loginRequest.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            User userDetails = (User) authentication.getPrincipal();
            String token = jwtUtil.generateToken(userDetails);

            logger.info("Login riuscito per l'utente: {} (ID: {})", userDetails.getUsername(), userDetails.getId());

            return AuthDto.AuthResponse.builder()
                    .token(token)
                    .userId(userDetails.getId())
                    .username(userDetails.getUsername())
                    .build();
        } catch (Exception e) {
            logger.warn("Login fallito per l'utente: {}, motivo: {}", loginRequest.getUsername(), e.getMessage());
            throw e;
        }
    }

    public AuthDto.AuthResponse register(UserDto.UserRequest userRequest) {
        logger.info("Tentativo di registrazione per l'utente: {}", userRequest.getUsername());

        try {
            // Creazione del nuovo utente
            User user = User.builder()
                    .username(userRequest.getUsername())
                    .email(userRequest.getEmail())
                    .passwordHash(userRequest.getPassword())
                    .build();

            User savedUser = userService.createUser(user);
            String token = jwtUtil.generateToken(savedUser);

            logger.info("Registrazione completata con successo per l'utente: {} (ID: {})", 
                    savedUser.getUsername(), savedUser.getId());

            return AuthDto.AuthResponse.builder()
                    .token(token)
                    .userId(savedUser.getId())
                    .username(savedUser.getUsername())
                    .build();
        } catch (Exception e) {
            logger.error("Errore durante la registrazione dell'utente: {}, motivo: {}", 
                    userRequest.getUsername(), e.getMessage(), e);
            throw e;
        }
    }
}
