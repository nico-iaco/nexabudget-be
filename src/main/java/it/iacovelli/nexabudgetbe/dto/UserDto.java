package it.iacovelli.nexabudgetbe.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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
        @Size(min = 12, message = "Password deve essere almeno 12 caratteri")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[a-zA-Z\\d@$!%*?&]+$",
                message = "Password deve contenere almeno una maiuscola, una minuscola, un numero e un simbolo speciale (@$!%*?&)")
        private String password;

        @Size(min = 3, max = 3, message = "La valuta di default deve essere di 3 caratteri (es. EUR, USD)")
        private String defaultCurrency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private UUID id;
        private String username;
        private String email;
        private String defaultCurrency;
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

        @Size(min = 3, max = 3, message = "La valuta di default deve essere di 3 caratteri (es. EUR, USD)")
        private String defaultCurrency;

        @Size(min = 12, message = "Password deve essere almeno 12 caratteri")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[a-zA-Z\\d@$!%*?&]*$",
                message = "Password deve contenere almeno una maiuscola, una minuscola, un numero e un simbolo speciale (@$!%*?&)")
        private String password;
    }
}
