package me.veselin.probity.simulation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.config.TradingConfiguration;
import me.veselin.probity.common.domain.event.DomainEventPublisher;
import me.veselin.probity.common.domain.event.SimulationCompletedEvent;
import me.veselin.probity.common.domain.event.SimulationFailedEvent;
import me.veselin.probity.common.domain.event.SimulationRequestedEvent;
import me.veselin.probity.common.util.DataUtil;
import me.veselin.probity.common.util.DateRangeUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.port.portfolio.PortfolioQueryPort;
import me.veselin.probity.risk.dto.DistributionStatistics;
import me.veselin.probity.risk.port.RiskPort;
import me.veselin.probity.simulation.domain.Simulation;
import me.veselin.probity.simulation.domain.SimulationPayload;
import me.veselin.probity.simulation.domain.SimulationPayload.*;
import me.veselin.probity.simulation.dto.SimulationData;
import me.veselin.probity.portfolio.service.portfolio.PortfolioValuationService;
import me.veselin.probity.simulation.exception.EmptyPortfolioException;
import me.veselin.probity.simulation.exception.SimulationNotFoundException;
import me.veselin.probity.simulation.mapper.SimulationMapper;
import me.veselin.probity.simulation.persistence.SimulationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
/**
 * Runs and retrieves Monte Carlo simulations for portfolio scenarios.
 *
 * <h2>Geometric Brownian Motion (GBM) Model</h2>
 * This service implements the GBM stochastic process for simulating portfolio value paths over time.
 * The GBM model assumes:
 * <ul>
 *   <li>Log-normal distribution of asset returns (prices cannot be negative)</li>
 *   <li>Constant drift parameter μ (expected return)</li>
 *   <li>Constant volatility parameter σ (standard deviation of returns)</li>
 *   <li>Independent, normally-distributed increments</li>
 * </ul>
 *
 * <h3>Mathematical Formulation</h3>
 * The discrete-time GBM equation used for simulation:
 * <pre>
 * S(t+Δt) = S(t) * exp((μ - σ²/2)Δt + σ√Δt * Z)
 * </pre>
 * Where:
 * <ul>
 *   <li>S(t) = portfolio value at time t</li>
 *   <li>μ = annualized drift (daily return rate)</li>
 *   <li>σ = annualized volatility (daily standard deviation)</li>
 *   <li>Δt = time step (1 trading day)</li>
 *   <li>Z = standard normal random variable</li>
 * </ul>
 *
 * <h3>Random Number Generation</h3>
 * Normally-distributed random numbers are generated using the Box-Muller transform:
 * <pre>
 * Z = √(-2 ln(U₁)) * cos(2πU₂)
 * </pre>
 * Where U₁ and U₂ are independent uniform random variables in (0,1).
 * This method is preferred over inverse transform sampling for its computational efficiency
 * and numerical stability.
 *
 * <h3>Parameter Estimation</h3>
 * When not provided by the caller, μ and σ are estimated from historical price data:
 * <ul>
 *   <li>μ = mean(daily returns) * trading days per year</li>
 *   <li>σ = std(daily returns) * √(trading days per year)</li>
 * </ul>
 * Default lookback period is 90 trading days (~4 months) to balance recency and statistical significance.
 */
public class MonteCarloSimulationService {

    // Historical data parameters
    private final TradingConfiguration tradingConfig;

    // Percentile bands materialised as path envelopes in the response
    private static final int[] PERCENTILES = {5, 10, 25, 50, 75, 90, 95};

    // Distribution histogram bucket count
    private static final int BUCKET_COUNT = 30; // Number of bins for final value distribution histogram

    // Risk outcome thresholds
    private static final double THRESHOLD_10PCT_LOSS = 0.90; // Portfolio value threshold for 10% loss scenario
    private static final double THRESHOLD_20PCT_LOSS = 0.80; // Portfolio value threshold for 20% loss scenario

    private final PortfolioQueryPort portfolioQueryPort;
    private final SimulationRepository simulationRepository;
    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;
    private final SimulationMapper simulationMapper;
    private final PortfolioValuationService portfolioValuationService;
    private final Executor simulationExecutor;
    private final DomainEventPublisher domainEventPublisher;

    // ── Public API ────────────────────────────────────────────────────────

    @Transactional
    /**
     * Executes a new simulation run for a user-owned portfolio and persists its payload.
     */
    public SimulationData run(UUID portfolioId, int numberOfSimulations, int timeHorizonDays, double confidenceLevel, Double assumedReturnPercent, Double assumedVolatilityPercent, UUID userId) {
        log.info("Running Monte Carlo simulation portfolioId={} userId={} paths={} horizon={}d",
                portfolioId, userId, numberOfSimulations, timeHorizonDays);

        // Publish requested event at start (before portfolio loading)
        domainEventPublisher.publish(new SimulationRequestedEvent(
                null, // simulationId not yet known
                portfolioId,
                userId,
                numberOfSimulations,
                timeHorizonDays,
                confidenceLevel,
                Instant.now()
        ));

        try {
            SimulationContext context = loadAndValidatePortfolio(portfolioId, userId);
            SimulationParameters params = prepareSimulationParameters(context.positions(), assumedReturnPercent, assumedVolatilityPercent);
            SimulationPayload payload = executeSimulationRun(context, params, numberOfSimulations, timeHorizonDays, confidenceLevel);

            Simulation saved = persistResults(portfolioId, userId, context.currentValue(), payload, numberOfSimulations, timeHorizonDays, confidenceLevel, assumedReturnPercent, assumedVolatilityPercent);

            log.info("Simulation saved id={}", saved.getId());

            // Publish completed event after successful persist
            domainEventPublisher.publish(new SimulationCompletedEvent(
                    saved.getId(),
                    portfolioId,
                    userId,
                    context.currentValue(),
                    Instant.now()
            ));

            return simulationMapper.toDto(saved);
        } catch (Exception e) {
            // Publish failed event on exception
            domainEventPublisher.publish(new SimulationFailedEvent(
                    portfolioId,
                    userId,
                    e.getMessage(),
                    Instant.now()
            ));
            // Rethrow original exception
            throw e;
        }
    }

    /**
     * Loads and validates the portfolio for simulation.
     */
    private SimulationContext loadAndValidatePortfolio(UUID portfolioId, UUID userId) {
        Portfolio portfolio = portfolioQueryPort.loadPortfolioWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) {
            throw new EmptyPortfolioException(
                    "Portfolio %s has no positions — cannot run simulation".formatted(portfolioId));
        }

        BigDecimal currentValue = computeCurrentValue(positions);
        log.info("computeCurrentValue result={} for {} positions", currentValue, positions.size());
        if (currentValue.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException(
                    "Portfolio value is zero — price fetch failed for all positions. Check ERROR logs above.");
        }

        return new SimulationContext(positions, currentValue);
    }

    /**
     * Prepares simulation parameters from portfolio data and request overrides.
     */
    private SimulationParameters prepareSimulationParameters(List<PortfolioPosition> positions, Double assumedReturnPercent, Double assumedVolatilityPercent) {
        double[] params = deriveParameters(positions, assumedReturnPercent, assumedVolatilityPercent);
        double dailyReturn = params[0];
        double dailyVolatility = params[1];

        log.debug("Simulation params dailyReturn={} dailyVol={}", dailyReturn, dailyVolatility);
        return new SimulationParameters(dailyReturn, dailyVolatility);
    }

    /**
     * Executes the Monte Carlo simulation run and builds the payload.
     */
    private SimulationPayload executeSimulationRun(SimulationContext context, SimulationParameters params, int numberOfSimulations, int timeHorizonDays, double confidenceLevel) {
        double currentValueDouble = context.currentValue().doubleValue();

        // Run GBM paths in parallel using dedicated executor
        double[][] paths = runParallelPaths(numberOfSimulations, timeHorizonDays, currentValueDouble, params.dailyReturn(), params.dailyVolatility());

        // Compute statistics and outcomes
        double[] finalValues = extractAndSortFinalValues(paths, numberOfSimulations, timeHorizonDays);
        Statistics statistics = computeStatistics(finalValues);
        Outcomes outcomes = computeOutcomes(finalValues, currentValueDouble, numberOfSimulations, confidenceLevel);

        // Convert to domain objects for payload
        List<SimulatedPath> allPaths = convertPathsToDomain(paths, numberOfSimulations);
        List<PercentileSeries> percentileSeries = buildPercentileSeries(allPaths, finalValues, currentValueDouble, timeHorizonDays);
        List<DistributionBucket> distribution = buildDistribution(finalValues, numberOfSimulations);

        return new SimulationPayload(statistics, outcomes, percentileSeries, allPaths, distribution);
    }

    /**
     * Runs GBM paths in parallel using the dedicated simulation executor.
     */
    private double[][] runParallelPaths(int n, int days, double currentValue, double dailyReturn, double dailyVolatility) {
        double[][] paths = new double[n][days + 1];

        List<CompletableFuture<Void>> futures = IntStream.range(0, n)
                .mapToObj(pathId -> CompletableFuture.runAsync(
                        () -> generatePathPrimitive(paths[pathId], currentValue, dailyReturn, dailyVolatility, days),
                        simulationExecutor))
                .toList();

        futures.forEach(CompletableFuture::join);
        return paths;
    }

    /**
     * Extracts final values from paths and sorts them for statistical analysis.
     */
    private double[] extractAndSortFinalValues(double[][] paths, int n, int days) {
        double[] finalValues = new double[n];
        for (int i = 0; i < n; i++) {
            finalValues[i] = paths[i][days];
        }
        Arrays.sort(finalValues);
        return finalValues;
    }

    /**
     * Converts primitive path arrays to domain objects for persistence.
     */
    private List<SimulatedPath> convertPathsToDomain(double[][] paths, int n) {
        List<SimulatedPath> allPaths = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            allPaths.add(convertToDomainPath(i, paths[i]));
        }
        return allPaths;
    }

    /**
     * Persists the simulation results to the database.
     */
    private Simulation persistResults(UUID portfolioId, UUID userId, BigDecimal currentValue,
                                     SimulationPayload payload, int numberOfSimulations, int timeHorizonDays, double confidenceLevel, Double assumedReturnPercent, Double assumedVolatilityPercent) {
        return simulationRepository.save(
                Simulation.builder()
                        .portfolioId(portfolioId)
                        .userId(userId)
                        .numberOfSimulations(numberOfSimulations)
                        .timeHorizonDays(timeHorizonDays)
                        .confidenceLevel(confidenceLevel)
                        .assumedReturnPct(assumedReturnPercent)
                        .assumedVolatilityPct(assumedVolatilityPercent)
                        .currentPortfolioValue(currentValue)
                        .resultPayload(payload)
                        .build()
        );
    }

    /**
     * Value object holding simulation context data.
     */
    private record SimulationContext(List<PortfolioPosition> positions, BigDecimal currentValue) {}

    /**
     * Value object holding simulation parameters.
     */
    private record SimulationParameters(double dailyReturn, double dailyVolatility) {}

    @Transactional(readOnly = true)
    /**
     * Loads a single simulation result owned by the requesting user.
     */
    public SimulationData get(UUID simulationId, UUID userId) {
        Simulation simulation = simulationRepository.findByIdAndUserId(simulationId, userId)
                .orElseThrow(() -> new SimulationNotFoundException(
                        "Simulation not found: " + simulationId));
        return simulationMapper.toDto(simulation);
    }

    @Transactional(readOnly = true)
    /**
     * Lists simulations previously run for a portfolio by the requesting user.
     */
    public List<SimulationData> listForPortfolio(UUID portfolioId, UUID userId) {
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
     * Optimized version using primitive array for memory efficiency.
     */
    private void generatePathPrimitive(double[] path, double s0,
                                       double dailyReturn, double dailyVol, int days) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        path[0] = s0;

        double drift = dailyReturn - 0.5 * dailyVol * dailyVol;
        double s = s0;

        for (int d = 1; d <= days; d++) {
            // Box-Muller transform for standard normal
            double u1 = rng.nextDouble();
            double u2 = rng.nextDouble();
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            s = s * Math.exp(drift + dailyVol * z);
            path[d] = Math.max(s, 0.0);
        }
    }

    /**
     * Converts primitive path array to domain object for persistence.
     * Used only for final payload construction.
     */
    private SimulatedPath convertToDomainPath(int pathId, double[] path) {
        List<PortfolioPath> values = new ArrayList<>(path.length);
        for (int d = 0; d < path.length; d++) {
            values.add(new PortfolioPath(d, path[d]));
        }
        return new SimulatedPath(pathId, values);
    }

    // ── Parameter derivation ──────────────────────────────────────────────

    /**
     * Returns [dailyReturn, dailyVolatility].
     * Uses caller overrides when present; otherwise derives from 90 days of market data.
     */
    private double[] deriveParameters(List<PortfolioPosition> positions,
                                      Double assumedReturnPercent, Double assumedVolatilityPercent) {

        boolean hasReturnOverride = assumedReturnPercent != null;
        boolean hasVolOverride = assumedVolatilityPercent != null;

        if (hasReturnOverride && hasVolOverride) {
            double annualReturn = assumedReturnPercent / 100.0;
            double annualVol = assumedVolatilityPercent / 100.0;
            double tradingDays = tradingConfig.getTradingDaysPerYear();
            return new double[]{annualReturn / tradingDays, annualVol / Math.sqrt(tradingDays)};
        }

        // Fetch historical bars to derive empirical μ and σ
        DateRangeUtil.DateRangeResult dateRange = DateRangeUtil.calculateHistoricalRange(tradingConfig.getHistoryLookbackDays());

        Map<String, List<PriceBar>> barsByTicker = positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        t -> t,
                        t -> marketDataPort.getHistoricalBars(t, dateRange.from(), dateRange.to())
                ));

        List<Double> portfolioReturns = buildPortfolioReturns(positions, barsByTicker);
        log.info("portfolioReturns size={} sample={}",
                portfolioReturns.size(),
                portfolioReturns.stream().limit(5).toList());

        double annualVol = portfolioReturns.size() >= 2
                ? riskPort.annualisedVolatility(portfolioReturns) / 100.0  // convert % → decimal
                : tradingConfig.getDefaultAnnualVolatility();

        double tradingDays = tradingConfig.getTradingDaysPerYear();
        double annualReturn = hasReturnOverride
                ? assumedReturnPercent / 100.0
                : portfolioReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * tradingDays;

        log.info("annualVol={} annualReturn={} dailyReturn={} dailyVol={}",
                annualVol, annualReturn,
                annualReturn / tradingDays,
                annualVol / Math.sqrt(tradingDays));

        double useVol = hasVolOverride
                ? assumedVolatilityPercent / 100.0
                : annualVol;

        return new double[]{annualReturn / tradingDays, useVol / Math.sqrt(tradingDays)};
    }

    private List<Double> buildPortfolioReturns(List<PortfolioPosition> positions,
                                               Map<String, List<PriceBar>> barsByTicker) {
        log.info("buildPortfolioReturns: barsByTicker keys={}, sizes={}",
                barsByTicker.keySet(),
                barsByTicker.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));

        Map<LocalDate, Double> valueByDate = DataUtil.buildValueSeries(positions, barsByTicker);
        log.info("buildPortfolioReturns: valueByDate size={}", valueByDate.size());

        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        if (values.size() < 2) {
            log.warn("buildPortfolioReturns: insufficient values size={} — falling back", values.size());
            return List.of();
        }

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
        double threshold10 = currentValue * THRESHOLD_10PCT_LOSS;
        double threshold20 = currentValue * THRESHOLD_20PCT_LOSS;

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
            // Use long arithmetic to prevent integer overflow, then clamp to valid bounds
            long targetIndexLong = (long) Math.floor((pct / 100.0) * (sortedFinalValues.length - 1L));
            int targetIndex = (int) Math.min(targetIndexLong, sortedFinalValues.length - 1L);
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
        // Use portfolio valuation service to decouple from market data internals
        return portfolioValuationService.computeCurrentValue(positions);
    }
}