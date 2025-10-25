package it.iacovelli.nexabudgetbe.controller;

import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

import java.time.format.DateTimeFormatter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Utenti", description = "Operazioni di gestione utente")
public class UserController {

    private final UserService userService;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna utente", description = "Aggiorna i dati di un utente esistente (username, email, password)")
    public ResponseEntity<UserDto.UserResponse> updateUser(@PathVariable Long id,
                                                           @Valid @RequestBody UserDto.UpdateUserRequest updateRequest) {
        User existingUser = userService.getUserById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        if (updateRequest.getUsername() != null) {
            existingUser.setUsername(updateRequest.getUsername());
        }
        if (updateRequest.getEmail() != null) {
            existingUser.setEmail(updateRequest.getEmail());
        }
        if (updateRequest.getPassword() != null) {
            existingUser.setPasswordHash(updateRequest.getPassword());
        }

        User updatedUser = userService.updateUser(existingUser);
        return ResponseEntity.ok(mapUserToResponse(updatedUser));
    }

    private UserDto.UserResponse mapUserToResponse(User user) {
        return UserDto.UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().format(formatter) : null)
                .build();
    }
}
