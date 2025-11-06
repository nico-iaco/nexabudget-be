package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AuthDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticazione", description = "Operazioni di autenticazione e registrazione (public)")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Effettua il login e restituisce il token JWT", security = {})
    public ResponseEntity<AuthDto.AuthResponse> login(@Valid @RequestBody AuthDto.LoginRequest loginRequest) {
        logger.debug("Richiesta di login ricevuta per l'utente: {}", loginRequest.getUsername());
        AuthDto.AuthResponse response = authService.login(loginRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    @Operation(summary = "Registrazione", description = "Registra un nuovo utente e restituisce il token JWT", security = {})
    public ResponseEntity<AuthDto.AuthResponse> register(@Valid @RequestBody UserDto.UserRequest registerRequest) {
        logger.debug("Richiesta di registrazione ricevuta per l'utente: {}", registerRequest.getUsername());
        AuthDto.AuthResponse response = authService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
