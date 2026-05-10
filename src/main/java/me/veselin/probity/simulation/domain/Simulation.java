package me.veselin.probity.simulation.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.veselin.probity.common.audit.BaseEntitySoftDelete;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregate root for the simulation bounded context.
 *
 * Extends BaseEntitySoftDelete — inherits:
 *   id (UUID), createdAt, updatedAt, deleted, deletedAt, version
 *
 * The full GBM result (paths, percentile series, statistics, distribution)
 * lives in result_payload (JSONB) — single-row lookup, no join overhead.
 *
 * current_portfolio_value uses NUMERIC(19,4) via @Column scale/precision,
 * consistent with portfolio_positions.quantity and price_bars.adj_close.
 */
@Entity
@Table(
        name = "simulations",
        indexes = {
                @Index(name = "idx_simulations_portfolio_id", columnList = "portfolio_id, created_at DESC"),
                @Index(name = "idx_simulations_user_id",      columnList = "user_id")
        }
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Simulation extends BaseEntitySoftDelete {

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // ── Request parameters ────────────────────────────────────────────────

    @Column(name = "number_of_simulations", nullable = false, updatable = false)
    private int numberOfSimulations;

    @Column(name = "time_horizon_days", nullable = false, updatable = false)
    private int timeHorizonDays;

    @Column(name = "confidence_level", nullable = false, updatable = false)
    private double confidenceLevel;

    /** Null when the service derived μ from market data rather than using a caller override. */
    @Column(name = "assumed_return_pct", updatable = false)
    private Double assumedReturnPct;

    /** Null when the service derived σ from market data rather than using a caller override. */
    @Column(name = "assumed_volatility_pct", updatable = false)
    private Double assumedVolatilityPct;

    // ── Snapshot ──────────────────────────────────────────────────────────

    /**
     * Live portfolio value fetched from market data at run time.
     * Stored as NUMERIC(19,4) — precision=19, scale=4 — matching price_bars and positions.
     */
    @Column(name = "current_portfolio_value", nullable = false, updatable = false,
            precision = 19, scale = 4)
    private BigDecimal currentPortfolioValue;

    // ── Result ────────────────────────────────────────────────────────────

    /**
     * Full GBM result stored as JSONB.
     * Shape: { statistics, outcomes, percentileSeries, allPaths, distribution }
     * Mirrors the frontend SimulationResult type exactly for zero-transform serialisation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload", nullable = false, updatable = false,
            columnDefinition = "jsonb")
    private SimulationPayload resultPayload;
}
