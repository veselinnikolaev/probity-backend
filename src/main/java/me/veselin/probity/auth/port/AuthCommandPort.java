package me.veselin.probity.auth.port;

import me.veselin.probity.auth.dto.LoginCommand;
import me.veselin.probity.auth.dto.RegisterCommand;
import me.veselin.probity.auth.dto.AuthResult;

public interface AuthCommandPort {
    AuthResult login(LoginCommand request);
    void register(RegisterCommand request);
    AuthResult refresh(String refreshToken);
    void logout(String refreshToken);
}
