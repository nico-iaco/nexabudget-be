package it.iacovelli.nexabudgetbe.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


public class UserDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRequest {
        @NotBlank(message = "Username è obbligatorio")
        @Size(min = 3, max = 50, message = "Username deve essere tra 3 e 50 caratteri")
        private String username;

        @NotBlank(message = "Email è obbligatoria")
        @Email(message = "Email deve essere valida")
        private String email;

        @NotBlank(message = "Password è obbligatoria")
        @Size(min = 8, message = "Password deve essere almeno 8 caratteri")
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private Long id;
        private String username;
        private String email;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateUserRequest {
        @Size(min = 3, max = 50, message = "Username deve essere tra 3 e 50 caratteri")
        private String username;

        @Email(message = "Email deve essere valida")
        private String email;

        @Size(min = 8, message = "Password deve essere almeno 8 caratteri")
        private String password;
    }
}
