package me.veselin.probity.bff.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for profile field updates. Email is immutable — not accepted here.
 */
public record UpdateProfileRequest(
        @NotBlank(message = "First name must not be blank")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @NotBlank(message = "Last name must not be blank")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName
) {}