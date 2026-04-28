package me.veselin.probity.portfolio.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.audit.BaseEntitySoftDelete;
import me.veselin.probity.portfolio.exception.PositionNotFoundException;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "portfolios",
        indexes = @Index(name = "idx_portfolio_user_id", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA requirement only
@SQLRestriction("deleted = false")
public class Portfolio extends BaseEntitySoftDelete {

    @Column(nullable = false)
    private String name;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @OneToMany(
            mappedBy = "portfolio",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private final List<PortfolioPosition> positions = new java.util.ArrayList<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static Portfolio create(String name, UUID userId) {
        Objects.requireNonNull(name,   "name must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("Portfolio name must not be blank");

        Portfolio p = new Portfolio();
        p.name   = name.trim();
        p.userId = userId;
        return p;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    public void addPosition(Asset asset, BigDecimal quantity) {
        Objects.requireNonNull(asset, "asset must not be null");
        requirePositiveQuantity(quantity, "addPosition");

        positions.stream()
                .filter(p -> p.getAsset().getId().equals(asset.getId()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.adjustQuantity(quantity),
                        () -> positions.add(new PortfolioPosition(this, asset, quantity))
                );
    }

    public void updatePositionQuantity(UUID positionId, BigDecimal newQuantity) {
        requirePositiveQuantity(newQuantity, "updatePositionQuantity");

        PortfolioPosition position = positions.stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new PositionNotFoundException(
                        "Position not found: " + positionId));

        // Delta = newQuantity - current, then adjustQuantity handles validation
        BigDecimal delta = newQuantity.subtract(position.getQuantity());
        position.adjustQuantity(delta);
    }

    public void removePosition(UUID positionId) {
        PortfolioPosition position = positions.stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new PositionNotFoundException(
                        "Position not found: " + positionId));

        position.softDelete();
        positions.remove(position);
    }

    public void reducePosition(Asset asset, BigDecimal quantity) {
        Objects.requireNonNull(asset, "asset must not be null");
        requirePositiveQuantity(quantity, "reducePosition");

        PortfolioPosition position = findActivePosition(asset);

        BigDecimal remaining = position.getQuantity().subtract(quantity);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            // Full sell — soft-delete the position
            position.softDelete();
            positions.remove(position);
        } else {
            // Partial sell — adjust quantity (negative delta)
            position.adjustQuantity(quantity.negate());
        }
    }

    public void rename(String newName) {
        Objects.requireNonNull(newName, "name must not be null");
        if (newName.isBlank()) throw new IllegalArgumentException("Portfolio name must not be blank");
        this.name = newName.trim();
    }

    @Override
    public void softDelete() {
        positions.forEach(PortfolioPosition::softDelete);
        super.softDelete();
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public List<PortfolioPosition> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private PortfolioPosition findActivePosition(Asset asset) {
        return positions.stream()
                .filter(p -> p.getAsset().getId().equals(asset.getId()))
                .findFirst()
                .orElseThrow(() -> new PositionNotFoundException(
                        "No active position for asset '%s' in portfolio '%s'"
                                .formatted(asset.getTicker(), this.name)));
    }

    private static void requirePositiveQuantity(BigDecimal quantity, String operation) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    operation + ": quantity must be positive, got: " + quantity);
        }
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Portfolio that)) return false;
        return getId() != null && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Portfolio{name='%s', userId=%s}".formatted(name, userId);
    }
}