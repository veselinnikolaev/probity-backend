package me.veselin.probity.portfolio.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.BaseEntityWithActive;
import me.veselin.probity.portfolio.enumeration.AssetType;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "assets",
        uniqueConstraints = @UniqueConstraint(name = "uq_assets_ticker", columnNames = "ticker")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA; use factory method for creation
@SQLRestriction("active = true")
public class Asset extends BaseEntityWithActive {

    @Column(nullable = false, unique = true, length = 20)
    private String ticker;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AssetType type;

    @OneToMany(mappedBy = "asset", fetch = FetchType.LAZY)
    private final List<PortfolioPosition> positions = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static Asset create(String ticker, String name, AssetType type) {
        Asset asset = new Asset();
        asset.ticker = Objects.requireNonNull(ticker, "ticker must not be null").toUpperCase();
        asset.name   = Objects.requireNonNull(name,   "name must not be null");
        asset.type   = Objects.requireNonNull(type,   "type must not be null");
        return asset;
    }

    // -------------------------------------------------------------------------
    // Read-only collection access
    // -------------------------------------------------------------------------

    public List<PortfolioPosition> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Asset that)) return false;
        // DB-identity equality: safe after first persist
        return getId() != null && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        // Constant is intentional: consistent before and after persist
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Asset{ticker='%s', type=%s}".formatted(ticker, type);
    }
}