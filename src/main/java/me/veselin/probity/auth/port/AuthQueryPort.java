package me.veselin.probity.auth.port;

import me.veselin.probity.auth.domain.User;

public interface AuthQueryPort {
    User getByUsername(String username);
}
