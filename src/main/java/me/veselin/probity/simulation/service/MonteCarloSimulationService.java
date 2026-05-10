package me.veselin.probity.simulation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.DataUtil;
import me.veselin.probity.common.util.TradingUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.port.PortfolioPort;
import me.veselin.probity.risk.dto.DistributionStatistics;
import me.veselin.probity.risk.port.RiskPort;
import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationPayload;
import me.veselin.probity.simulation.domain.SimulationPayload.*;
import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResultDto;
import me.veselin.probity.simulation.exception.SimulationNotFoundException;
import me.veselin.probity.simulation.mapper.SimulationMapper;
import me.veselin.probity.simulation.repository.SimulationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonteCarloSimulationService {

    // 90 trading days (~4 months) of history used to derive μ and σ when
    // the caller does not supply explicit overrides.
    private static final int HISTORY_DAYS = 90;

    // Percentile bands materialised as path envelopes in the response
    private static final int[] PERCENTILES = {5, 10, 25, 50, 75, 90, 95};

    // Distribution histogram bucket count
    private static final int BUCKET_COUNT = 30;

    private final PortfolioPort portfolioPort;
    private final SimulationRepository simulationRepository;
    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;
    private final SimulationMapper simulationMapper;

    // ── Public API ────────────────────────────────────────────────────────

    @Transactional
    public SimulationResultDto run(RunSimulationRequest request, UUID userId) {
        log.info("Running Monte Carlo simulation portfolioId={} userId={} paths={} horizon={}d",
                request.portfolioId(), userId, request.numberOfSimulations(), request.timeHorizonDays());

        Portfolio portfolio = portfolioPort.loadPortfolioWithPositions(request.portfolioId(), userId);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Portfolio %s has no positions — cannot run simulation".formatted(request.portfolioId()));
        }

        // ── Derive μ and σ from real market data (or use caller overrides) ──
        BigDecimal currentValue = computeCurrentValue(positions);
        double currentValueDouble = currentValue.doubleValue();
        double[] params = deriveParameters(positions, request);
        double dailyReturn = params[0];
        double dailyVolatility = params[1];

        log.debug("Simulation params currentValue={} dailyReturn={} dailyVol={}",
                currentValue, dailyReturn, dailyVolatility);

        // ── Run GBM paths in parallel ─────────────────────────────────────
        int n = request.numberOfSimulations();
        int days = request.timeHorizonDays();

        List<SimulatedPath> allPaths = IntStream.range(0, n)
                .parallel()
                .mapToObj(pathId -> generatePath(pathId, currentValueDouble, dailyReturn, dailyVolatility, days))
                .collect(Collectors.toList());

        // ── Compute statistics from final values ──────────────────────────
        double[] finalValues = allPaths.stream()
                .mapToDouble(p -> p.values().getLast().value())
                .toArray();
        Arrays.sort(finalValues);

        Statistics statistics = computeStatistics(finalValues);
        Outcomes outcomes = computeOutcomes(finalValues, currentValueDouble, n, request.confidenceLevel());

        // ── Percentile envelopes ──────────────────────────────────────────
        List<PercentileSeries> percentileSeries = buildPercentileSeries(allPaths, finalValues, currentValueDouble, days);

        // ── Distribution histogram ────────────────────────────────────────
        List<DistributionBucket> distribution = buildDistribution(finalValues, n);

        // ── Persist ───────────────────────────────────────────────────────
        SimulationPayload payload = new SimulationPayload(
                statistics, outcomes, percentileSeries, allPaths, distribution);

        Simulation saved = simulationRepository.save(
                Simulation.builder()
                        .portfolioId(request.portfolioId())
                        .userId(userId)
                        .numberOfSimulations(n)
                        .timeHorizonDays(days)
                        .confidenceLevel(request.confidenceLevel())
                        .assumedReturnPct(request.assumedReturnPercent())
                        .assumedVolatilityPct(request.assumedVolatilityPercent())
                        .currentPortfolioValue(currentValue)
                        .resultPayload(payload)
                        .build()
        );

        log.info("Simulation saved id={}", saved.getId());
        return simulationMapper.toDto(saved);

    }

    @Transactional(readOnly = true)
    public SimulationResultDto get(UUID simulationId, UUID userId) {
        Simulation simulation = simulationRepository.findByIdAndUserId(simulationId, userId)
                .orElseThrow(() -> new SimulationNotFoundException(
                        "Simulation not found: " + simulationId));
        return simulationMapper.toDto(simulation);
    }

    @Transactional(readOnly = true)
    public List<SimulationResultDto> listForPortfolio(UUID portfolioId, UUID userId) {
        // Ownership of the portfolio is implicitly enforced: we only return
        // rows where user_id matches — no separate portfolio auth needed.
        return simulationRepository
                .findByPortfolioIdAndUserId(portfolioId, userId)
                .stream()
                .map(simulationMapper::toDto)
                .toList();
    }

    // ── GBM path generation ───────────────────────────────────────────────

    /**
     * Geometric Brownian Motion path: S(t+1) = S(t) × exp((μ - ½σ²)dt + σ√dt × Z)
     * where Z ~ N(0,1) and dt = 1 (one trading day).
     */
    private SimulatedPath generatePath(int pathId, double s0,
                                       double dailyReturn, double dailyVol, int days) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<PortfolioPath> values = new ArrayList<>(days + 1);
        values.add(new PortfolioPath(0, s0));

        double drift = dailyReturn - 0.5 * dailyVol * dailyVol;
        double s = s0;

        for (int d = 1; d <= days; d++) {
            // Box-Muller transform for standard normal
            double u1 = rng.nextDouble();
            double u2 = rng.nextDouble();
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            s = s * Math.exp(drift + dailyVol * z);
            values.add(new PortfolioPath(d, Math.max(s, 0.0)));
        }

        return new SimulatedPath(pathId, values);
    }

    // ── Parameter derivation ──────────────────────────────────────────────

    /**
     * Returns [dailyReturn, dailyVolatility].
     * Uses caller overrides when present; otherwise derives from 90 days of market data.
     */
    private double[] deriveParameters(List<PortfolioPosition> positions,
                                      RunSimulationRequest request) {

        boolean hasReturnOverride = request.assumedReturnPercent() != null;
        boolean hasVolOverride = request.assumedVolatilityPercent() != null;

        if (hasReturnOverride && hasVolOverride) {
            double annualReturn = request.assumedReturnPercent() / 100.0;
            double annualVol = request.assumedVolatilityPercent() / 100.0;
            return new double[]{annualReturn / 252.0, annualVol / Math.sqrt(252.0)};
        }

        // Fetch historical bars to derive empirical μ and σ
        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = to.minusDays(HISTORY_DAYS);

        Map<String, List<PriceBar>> barsByTicker = positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        t -> t,
                        t -> marketDataPort.getHistoricalBars(t, from, to)
                ));

        List<Double> portfolioReturns = buildPortfolioReturns(positions, barsByTicker);

        double annualVol = portfolioReturns.size() >= 2
                ? riskPort.annualisedVolatility(portfolioReturns)
                : 0.15; // fallback: 15%

        double annualReturn = hasReturnOverride
                ? request.assumedReturnPercent() / 100.0
                : portfolioReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 252.0;

        double useVol = hasVolOverride
                ? request.assumedVolatilityPercent() / 100.0
                : annualVol;

        return new double[]{annualReturn / 252.0, useVol / Math.sqrt(252.0)};
    }

    private List<Double> buildPortfolioReturns(List<PortfolioPosition> positions,
                                               Map<String, List<PriceBar>> barsByTicker) {
        // Reuse DataUtil + RiskPort patterns consistent with PortfolioQueryService
        Map<LocalDate, Double> valueByDate = DataUtil.buildValueSeries(positions, barsByTicker);
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        if (values.size() < 2) return List.of();

        List<Double> returns = new ArrayList<>(values.size() - 1);
        for (int i = 1; i < values.size(); i++) {
            double prev = values.get(i - 1);
            if (prev != 0.0) returns.add((values.get(i) - prev) / prev);
        }
        return returns;
    }

    // ── Statistics ────────────────────────────────────────────────────────

    private Statistics computeStatistics(double[] sorted) {
        // RiskPort.distributionStatistics uses Welford's algorithm (numerically
        // stable single-pass) and correctly handles even-N median.
        DistributionStatistics ds =
                riskPort.distributionStatistics(sorted);

        return new Statistics(
                ds.mean(),
                ds.median(),
                ds.stdDeviation(),
                ds.min(),
                ds.max()
        );
    }


    private Outcomes computeOutcomes(double[] sorted, double currentValue,
                                     int n, double confidenceLevel) {
        double threshold10 = currentValue * 0.90;
        double threshold20 = currentValue * 0.80;

        long count10 = 0, count20 = 0;
        for (double v : sorted) {
            if (v < threshold10) count10++;
            if (v < threshold20) count20++;
        }

        // Delegate empirical VaR and CVaR to RiskPort — single source of truth
        double var   = riskPort.simulationVaR(sorted, confidenceLevel);
        double cvar  = riskPort.conditionalValueAtRisk(sorted, confidenceLevel);

        return new Outcomes(
                (double) count10 / n * 100.0,
                (double) count20 / n * 100.0,
                var,
                cvar
        );
    }

    // ── Percentile envelopes ──────────────────────────────────────────────

    /**
     * For each percentile we pick the path whose final value is closest to
     * the target percentile of the sorted final-value distribution, giving a
     * realistic (non-smoothed) representative trajectory.
     */
    private List<PercentileSeries> buildPercentileSeries(List<SimulatedPath> allPaths,
                                                         double[] sortedFinalValues,
                                                         double currentValue,
                                                         int days) {
        List<PercentileSeries> result = new ArrayList<>(PERCENTILES.length);

        for (int pct : PERCENTILES) {
            int targetIndex = (int) Math.floor((pct / 100.0) * (sortedFinalValues.length - 1));
            double targetFinalValue = sortedFinalValues[targetIndex];

            // Find the actual path whose final value is closest to this percentile target
            SimulatedPath representative = allPaths.stream()
                    .min(Comparator.comparingDouble(
                            p -> Math.abs(p.values().getLast().value() - targetFinalValue)))
                    .orElseThrow();

            result.add(new PercentileSeries(pct, targetFinalValue, representative.values()));
        }

        return result;
    }

    // ── Distribution histogram ────────────────────────────────────────────

    private List<DistributionBucket> buildDistribution(double[] sorted, int n) {
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        double width = (max - min) / BUCKET_COUNT;

        List<DistributionBucket> buckets = new ArrayList<>(BUCKET_COUNT);

        for (int i = 0; i < BUCKET_COUNT; i++) {
            double lo = min + i * width;
            double hi = lo + width;
            int count = 0;
            for (double v : sorted) {
                if (v >= lo && v < hi) count++;
            }
            buckets.add(new DistributionBucket(lo, hi, count, (double) count / n * 100.0));
        }

        return buckets;
    }

    // ── Portfolio value ───────────────────────────────────────────────────

    private BigDecimal computeCurrentValue(List<PortfolioPosition> positions) {
        return positions.stream()
                .map(pos -> {
                    try {
                        BigDecimal price = marketDataPort.getLatestPrice(pos.getAsset().getTicker());
                        return price.multiply(pos.getQuantity());
                    } catch (Exception e) {
                        log.warn("Failed to fetch price for ticker={}", pos.getAsset().getTicker(), e);
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}