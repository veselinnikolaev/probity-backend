package me.veselin.probity.bff.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BFF request DTO for self-service account registration.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 2, max = 50)
        String firstName,

        @NotBlank @Size(min = 2, max = 50)
        String lastName,

        @NotBlank @Size(min = 3, max = 20)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                message = "Username can contain only letters, numbers and underscore")
        String username,

        @NotBlank @Email @Size(max = 100)
        String email,

        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,64}$",
                message = "Password must contain uppercase, lowercase, number and special character"
        )
        String password
) {
}
