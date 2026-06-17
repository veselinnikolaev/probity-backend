package me.veselin.probity.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.audit.BaseEntitySoftDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Child entity of the `Portfolio` aggregate representing one held instrument.
 * Guarantees strictly positive quantity and weighted-average cost consistency.
 */
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

    @Column(name = "avg_buy_price", precision = 19, scale = 4)
    private BigDecimal avgBuyPrice;


    PortfolioPosition(Portfolio portfolio, Asset asset, BigDecimal quantity, BigDecimal price) {
        this.portfolio = portfolio;
        this.asset = asset;
        this.quantity = validateQuantity(quantity);
        this.avgBuyPrice = price;
    }


    /**
     * Adjusts position quantity by delta and recalculates weighted-average cost on buys.
     * Enforces strictly positive quantity invariant.
     */
    void adjustQuantity(BigDecimal delta, BigDecimal price) {
        BigDecimal newQty = this.quantity.add(delta);

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            // BUY → recalc weighted average
            BigDecimal totalCost =
                    this.avgBuyPrice.multiply(this.quantity)
                            .add(price.multiply(delta));

            this.avgBuyPrice = totalCost.divide(newQty, 4, RoundingMode.HALF_UP);
        }

        this.quantity = validateQuantity(newQty);
    }


    private static BigDecimal validateQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Position quantity must be positive, got: " + quantity);
        }
        return quantity;
    }


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