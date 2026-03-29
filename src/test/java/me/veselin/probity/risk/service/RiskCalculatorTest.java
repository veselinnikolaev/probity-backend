package me.veselin.probity.risk.service;

import me.veselin.probity.portfolio.enumeration.AssetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RiskCalculatorTest {

    private RiskCalculator calculator;

    // Two-element return series: [+10%, -5%] → mean = 0.025, used across many tests
    private static final List<Double> RETURNS_SIMPLE = List.of(0.10, -0.05);

    // Flat returns (zero variance) → vol = 0, sharpe = 0 by design
    private static final List<Double> RETURNS_FLAT = List.of(0.01, 0.01, 0.01);

    @BeforeEach
    void setUp() {
        calculator = new RiskCalculator();
        // Inject the @Value field manually (Spring isn't loaded here)
        setRiskFreeRate(calculator, 0.045);
    }

    // ── annualisedVolatility ─────────────────────────────────────────────────

    @Test
    void volatility_returnsZero_whenFewerThanTwoReturns() {
        assertThat(calculator.annualisedVolatility(List.of())).isZero();
        assertThat(calculator.annualisedVolatility(List.of(0.05))).isZero();
    }

    @Test
    void volatility_isPositive_forNonFlatReturns() {
        double vol = calculator.annualisedVolatility(RETURNS_SIMPLE);
        assertThat(vol).isGreaterThan(0.0);
    }

    @Test
    void volatility_isZero_forFlatReturns() {
        assertThat(calculator.annualisedVolatility(RETURNS_FLAT)).isZero();
    }

    @Test
    void volatility_scalesWithSqrtTradingDays() {
        // vol = stdDev(returns) * sqrt(252) * 100
        // stdDev of [+0.1, -0.05]: mean = 0.025, deviations = [0.075, -0.075], variance = 0.005625
        double stdDev = Math.sqrt(0.005625);
        double expected = stdDev * Math.sqrt(252) * 100;
        double actual = calculator.annualisedVolatility(RETURNS_SIMPLE);
        assertThat(actual).isCloseTo(expected, within(1e-9));
    }

    // ── sharpeRatio ──────────────────────────────────────────────────────────

    @Test
    void sharpe_returnsZero_whenFewerThanTwoReturns() {
        assertThat(calculator.sharpeRatio(List.of())).isZero();
    }

    @Test
    void sharpe_returnsZero_forFlatReturns() {
        // stdDev = 0 → division guard kicks in
        assertThat(calculator.sharpeRatio(RETURNS_FLAT)).isZero();
    }

    @Test
    void sharpe_isPositive_whenMeanExceedsRiskFreeRate() {
        // daily mean of [0.1, -0.05] = 0.025 >> risk-free daily = 0.045/252 ≈ 0.000178
        assertThat(calculator.sharpeRatio(RETURNS_SIMPLE)).isGreaterThan(0.0);
    }

    @Test
    void sharpe_matchesManualFormula() {
        double rfDaily = 0.045 / 252.0;
        double mean = calculator.mean(RETURNS_SIMPLE);
        double stdDev = Math.sqrt(calculator.variance(RETURNS_SIMPLE, mean));
        double expected = ((mean - rfDaily) / stdDev) * Math.sqrt(252.0);
        assertThat(calculator.sharpeRatio(RETURNS_SIMPLE)).isCloseTo(expected, within(1e-9));
    }

    // ── var95 ────────────────────────────────────────────────────────────────

    @Test
    void var95_returnsZero_whenFewerThanTwoReturns() {
        assertThat(calculator.var95(10_000, List.of())).isZero();
    }

    @Test
    void var95_isPositive_forNonFlatReturns() {
        assertThat(calculator.var95(100_000, RETURNS_SIMPLE)).isGreaterThan(0.0);
    }

    @Test
    void var95_scalesLinearlyWithPortfolioValue() {
        double v1 = calculator.var95(100_000, RETURNS_SIMPLE);
        double v2 = calculator.var95(200_000, RETURNS_SIMPLE);
        assertThat(v2).isCloseTo(v1 * 2, within(1e-6));
    }

    @Test
    void var95_matchesManualFormula() {
        double mean = calculator.mean(RETURNS_SIMPLE);
        double stdDev = Math.sqrt(calculator.variance(RETURNS_SIMPLE, mean));
        double expected = 50_000 * 1.645 * stdDev;
        assertThat(calculator.var95(50_000, RETURNS_SIMPLE)).isCloseTo(expected, within(1e-9));
    }

    // ── toDailyReturns ───────────────────────────────────────────────────────

    @Test
    void toDailyReturns_returnsEmpty_forSinglePrice() {
        assertThat(calculator.toDailyReturns(List.of(100.0))).isEmpty();
    }

    @Test
    void toDailyReturns_computesSimpleReturnsCorrectly() {
        // [100, 110, 99] → [+10%, -10%]
        List<Double> result = calculator.toDailyReturns(List.of(100.0, 110.0, 99.0));
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isCloseTo(0.10, within(1e-9));
        assertThat(result.get(1)).isCloseTo(-0.10, within(1e-9));
    }

    @Test
    void toDailyReturns_skipsZeroPrevPrice() {
        // prev = 0 should be skipped (no division by zero)
        List<Double> result = calculator.toDailyReturns(List.of(0.0, 100.0, 110.0));
        // index 0→1 skipped (prev=0), index 1→2 = +10%
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isCloseTo(0.10, within(1e-9));
    }

    // ── rollingVolatility ────────────────────────────────────────────────────

    @Test
    void rollingVolatility_returnsSameLengthAsReturns() {
        List<Double> prices = List.of(100.0, 102.0, 101.0, 103.0, 105.0);
        List<Double> result = calculator.rollingVolatility(prices, 3);
        // prices has 5 → 4 daily returns → rolling vol also has 4 entries
        assertThat(result).hasSize(4);
    }

    @Test
    void rollingVolatility_isAllZero_forFlatPrices() {
        List<Double> flatPrices = List.of(100.0, 100.0, 100.0, 100.0, 100.0);
        List<Double> result = calculator.rollingVolatility(flatPrices, 3);
        result.forEach(v -> assertThat(v).isZero());
    }

    // ── riskScore & riskLevel ────────────────────────────────────────────────

    @Test
    void riskScore_clampedToHundred() {
        // Extremely high vol should not push score above 100
        int score = calculator.riskScore(AssetType.CRYPTO.name(), 999.0);
        assertThat(score).isEqualTo(100);
    }

    @Test
    void riskScore_minimumIsBaseScore_whenVolBelow15Pct() {
        int base = AssetType.STOCK.getBaseRiskScore();
        int score = calculator.riskScore(AssetType.STOCK.name(), 10.0);
        // vol ≤ 15 → volAddon = max(0, ...) = 0
        assertThat(score).isEqualTo(base);
    }

    @Test
    void riskScore_increasesWithVol() {
        int lowVolScore = calculator.riskScore(AssetType.STOCK.name(), 20.0);
        int highVolScore = calculator.riskScore(AssetType.STOCK.name(), 50.0);
        assertThat(highVolScore).isGreaterThan(lowVolScore);
    }

    @Test
    void riskLevel_returnsLow_forLowScore() {
        String level = calculator.riskLevel(5);
        assertThat(level).isEqualTo("LOW");
    }

    @Test
    void riskLevel_returnsHigh_forHighScore() {
        String level = calculator.riskLevel(95);
        assertThat(level).isEqualTo("HIGH");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Reflectively set the @Value-injected riskFreeRate.
     */
    private static void setRiskFreeRate(RiskCalculator calc, double rate) {
        try {
            var field = RiskCalculator.class.getDeclaredField("riskFreeRate");
            field.setAccessible(true);
            field.set(calc, rate);
        } catch (Exception e) {
            throw new RuntimeException("Could not inject riskFreeRate", e);
        }
    }
}

