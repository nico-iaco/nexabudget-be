package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Utenti", description = "Operazioni di gestione utente")
public class UserController {

    private final UserService userService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PutMapping("/")
    @Operation(summary = "Aggiorna utente", description = "Aggiorna parzialmente i dati dell'utente loggato. Vengono aggiornati solo i campi presenti nella richiesta.")
    public ResponseEntity<UserDto.UserResponse> updateUser(@AuthenticationPrincipal User currentUser,
                                                           @Valid @RequestBody UserDto.UpdateUserRequest updateRequest) {
        User existingUser = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        User updatedUser = userService.updateUserProfile(
                existingUser,
                updateRequest.getUsername(),
                updateRequest.getEmail(),
                updateRequest.getPassword(),
                updateRequest.getDefaultCurrency());
        return ResponseEntity.ok(mapUserToResponse(updatedUser));
    }

    private UserDto.UserResponse mapUserToResponse(User user) {
        return UserDto.UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .defaultCurrency(user.getDefaultCurrency())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().format(formatter) : null)
                .build();
    }
}
