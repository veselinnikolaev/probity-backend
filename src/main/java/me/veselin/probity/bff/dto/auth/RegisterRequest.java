package me.veselin.probity.bff.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BFF request DTO for self-service account registration.
 */
public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 20)
        @Pattern(
                regexp = "^[a-zA-Z0-9_]+$",
                message = "Username can contain only letters, numbers and underscore"
        )
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email")
        @Size(max = 100)
        String email,

        @NotBlank(message = "Password is required")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,64}$",
                message = "Password must contain uppercase, lowercase, number and special character"
        )
        String password
) {
}
