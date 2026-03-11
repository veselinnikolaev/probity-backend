package me.veselin.probity.auth.service;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.domain.User;
import me.veselin.probity.auth.dto.AuthResponse;
import me.veselin.probity.auth.dto.LoginRequest;
import me.veselin.probity.auth.dto.RegisterRequest;
import me.veselin.probity.auth.jwt.JwtService;
import me.veselin.probity.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request){
        User user = userRepository
                .findByUsername(request.username())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(
                request.password(),
                user.getPassword())) {

            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtService.generateJwt(user.getUsername(), Map.of("role", user.getRole().name()));

        return new AuthResponse(token);
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username).orElseThrow(
                () -> new RuntimeException("User not found")
        );
    }

    public void register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new RuntimeException("Username already taken");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email already registered");
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = User.create(request.username(), request.email(), hashedPassword);
        userRepository.save(user);
    }
}
