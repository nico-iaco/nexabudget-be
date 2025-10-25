package it.iacovelli.nexabudgetbe.controller;

import it.iacovelli.nexabudgetbe.dto.AuthDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticazione", description = "Operazioni di autenticazione e registrazione (public)")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Effettua il login e restituisce il token JWT", security = {})
    public ResponseEntity<AuthDto.AuthResponse> login(@Valid @RequestBody AuthDto.LoginRequest loginRequest) {
        try {
            AuthDto.AuthResponse response = authService.login(loginRequest);
            return ResponseEntity.ok(response);
        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenziali non valide");
        }
    }

    @PostMapping("/register")
    @Operation(summary = "Registrazione", description = "Registra un nuovo utente e restituisce il token JWT", security = {})
    public ResponseEntity<AuthDto.AuthResponse> register(@Valid @RequestBody UserDto.UserRequest registerRequest) {
        try {
            AuthDto.AuthResponse response = authService.register(registerRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
