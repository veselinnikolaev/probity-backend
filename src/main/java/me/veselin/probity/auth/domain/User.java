package me.veselin.probity.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.audit.BaseEntitySoftDelete;
import me.veselin.probity.auth.enumeration.Role;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * Aggregate root for the auth context representing an application identity.
 * Preserves credential ownership and role assignment invariants.
 */
@Entity
@Table(
        name = "users",
        indexes = @Index(name = "idx_users_email", columnList = "email")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted = false")
public class User extends BaseEntitySoftDelete implements Serializable {

    @Column(name = "username", length = 100)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private Role role;


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


    public void changePassword(String newHashedPassword) {
        this.password = Objects.requireNonNull(newHashedPassword, "hashedPassword must not be null");
    }

    public void updateUsername(String username) {
        this.username = username != null ? username.trim() : null;
    }

    public void updateProfile(String firstName, String lastName) {
        this.firstName = firstName != null ? firstName.trim() : null;
        this.lastName = lastName != null ? lastName.trim() : null;
    }

    public void promoteToAdmin() {
        this.role = Role.ADMIN;
    }


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
        return "User{id=%s, firstName=%s, lastName=%s, role=%s}".formatted(getId(), firstName, lastName, role);
    }
}