package me.veselin.probity.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import me.veselin.probity.common.BaseEntitySoftDelete;
import me.veselin.probity.marketdata.dto.PriceBarDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "price_bars",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "bar_date"}))
@Getter
public class PriceBar extends BaseEntitySoftDelete {

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "bar_date", nullable = false)
    private LocalDate barDate;

    @Column(precision = 19, scale = 4)
    private BigDecimal open;

    @Column(precision = 19, scale = 4)
    private BigDecimal high;

    @Column(precision = 19, scale = 4)
    private BigDecimal low;

    @Column(precision = 19, scale = 4)
    private BigDecimal close;

    @Column(name = "adj_close", precision = 19, scale = 4)
    private BigDecimal adjClose;

    private Long volume;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static PriceBar from(PriceBarDto dto) {
        Objects.requireNonNull(dto, "PriceBarDto must not be null");
        PriceBar bar = new PriceBar();
        bar.symbol = dto.symbol();
        bar.barDate = dto.barDate();
        bar.open = dto.open();
        bar.high = dto.high();
        bar.low = dto.low();
        bar.close = dto.close();
        bar.adjClose = dto.adjClose();
        bar.volume = dto.volume();
        return bar;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /**
     * Computes the daily return against the previous bar's adjClose.
     * Used by the risk engine to build the return series for VaR and volatility.
     * Always uses adjClose — never raw close — to account for splits and dividends.
     */
    public double dailyReturn(PriceBar previousBar) {
        Objects.requireNonNull(previousBar, "Previous bar must not be null");
        validateAdjClose();
        previousBar.validateAdjClose();

        BigDecimal prev = previousBar.adjClose;
        return adjClose.subtract(prev)
                .divide(prev, 10, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * The intraday range — high minus low.
     * Useful for volatility estimation and stress testing.
     */
    public BigDecimal intradayRange() {
        if (high == null || low == null) return BigDecimal.ZERO;
        return high.subtract(low);
    }

    /**
     * Whether this bar represents a positive (green) session.
     */
    public boolean isBullish() {
        if (close == null || open == null) return false;
        return close.compareTo(open) > 0;
    }

    /**
     * Whether this bar's adjClose is above a given price threshold.
     * Useful for regime detection filters later.
     */
    public boolean isAbove(BigDecimal threshold) {
        Objects.requireNonNull(threshold, "Threshold must not be null");
        validateAdjClose();
        return adjClose.compareTo(threshold) > 0;
    }

    private void validateAdjClose() {
        if (adjClose == null) {
            throw new IllegalStateException(
                    "adjClose is null for bar: " + symbol + " on " + barDate
            );
        }
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PriceBar that)) return false;
        return getId() != null && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "PriceBar{symbol='%s', date=%s, adjClose=%s}"
                .formatted(symbol, barDate, adjClose);
    }
}