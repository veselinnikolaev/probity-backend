package me.veselin.probity.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.BaseEntitySoftDelete;
import me.veselin.probity.auth.enumeration.Role;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(
        name = "users",
        indexes = @Index(name = "idx_users_email", columnList = "email")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA requirement only
@SQLRestriction("deleted = false")
public class User extends BaseEntitySoftDelete implements Serializable {

    @Column(name = "username", length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, length = 20)
    private Role role;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static User create(String username, String email, String hashedPassword) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(hashedPassword, "hashedPassword must not be null");

        User user = new User();
        user.username = username.trim();
        user.email = email.toLowerCase().trim();
        user.password = hashedPassword;
        user.role = Role.USER;
        return user;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    public void changePassword(String newHashedPassword) {
        this.password = Objects.requireNonNull(newHashedPassword, "hashedPassword must not be null");
    }

    public void updateUsername(String username) {
        this.username = username != null ? username.trim() : null;
    }

    /**
     * Elevates this user to ADMIN. Should only be called from an
     * admin management service with appropriate authorization.
     */
    public void promoteToAdmin() {
        this.role = Role.ADMIN;
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User that)) return false;
        return getId() != null && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // Deliberately excludes email for privacy in logs
        return "User{id=%s, role=%s}".formatted(getId(), role);
    }
}