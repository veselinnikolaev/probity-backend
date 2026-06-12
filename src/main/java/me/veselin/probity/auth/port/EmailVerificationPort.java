package me.veselin.probity.auth.port;

public interface EmailVerificationPort {
    void sendVerificationEmail(String email);
    void verify(String token);
    void resendVerification(String email);
}
