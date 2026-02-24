package me.veselin.probity.portfolio.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.BaseEntitySoftDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(
        name = "portfolio_positions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_position_portfolio_asset",
                columnNames = {"portfolio_id", "asset_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA + aggregate root access only
@SQLRestriction("deleted = false")
public class PortfolioPosition extends BaseEntitySoftDelete {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false, updatable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, updatable = false)
    private Asset asset;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // -------------------------------------------------------------------------
    // Package-private constructor — called only by Portfolio aggregate root
    // -------------------------------------------------------------------------

    PortfolioPosition(Portfolio portfolio, Asset asset, BigDecimal quantity) {
        this.portfolio = Objects.requireNonNull(portfolio, "portfolio must not be null");
        this.asset     = Objects.requireNonNull(asset,     "asset must not be null");
        this.quantity  = validateQuantity(quantity);
    }

    // -------------------------------------------------------------------------
    // Domain behaviour — package-private, invoked only by Portfolio
    // -------------------------------------------------------------------------

    void adjustQuantity(BigDecimal delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        BigDecimal updated = this.quantity.add(delta);
        this.quantity = validateQuantity(updated);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static BigDecimal validateQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Position quantity must be positive, got: " + quantity);
        }
        return quantity;
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortfolioPosition that)) return false;
        return getId() != null && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "PortfolioPosition{asset=%s, quantity=%s}".formatted(
                asset != null ? asset.getTicker() : "?", quantity);
    }
}