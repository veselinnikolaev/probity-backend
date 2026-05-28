package me.veselin.probity.portfolio.service.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.DataUtil;
import me.veselin.probity.common.util.SeriesUtil;
import me.veselin.probity.common.util.TradingUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.enumeration.AssetType;
import me.veselin.probity.portfolio.enumeration.DateRange;
import me.veselin.probity.portfolio.enumeration.Sector;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
import me.veselin.probity.portfolio.port.PortfolioPort;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import me.veselin.probity.risk.port.RiskPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
/**
 * Query-side portfolio read model assembler used by BFF endpoints.
 */
public class PortfolioQueryService implements PortfolioPort {

    private static final int MIN_RETURNS_FOR_METRICS = 5;
    private final PortfolioRepository portfolioRepository;
    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;

    @Override
    /**
     * Loads a portfolio aggregate with positions while enforcing owner-level access.
     */
    public Portfolio loadPortfolioWithPositions(UUID id, UUID principalId) {
        Portfolio portfolio = portfolioRepository.findByIdWithPositions(id)
                .orElseThrow(() -> {
                    log.error("Portfolio not found id={}", id);
                    return new PortfolioNotFoundException("Portfolio not found: " + id);
                });
        if (!portfolio.getUserId().equals(principalId)) {
            throw new AccessDeniedException("Access denied to portfolio: " + id);
        }
        return portfolio;
    }

    // ── Portfolios list ──────────────────────────────────────────────────────

    /**
     * Builds portfolio cards with current valuation and compact risk indicators.
     */
    public List<PortfolioDto> getPortfolios(UUID userId) {
        log.debug("Fetching portfolios for userId={}", userId);

        return portfolioRepository.findByUserIdWithPositions(userId).stream()
                .map(p -> {
                    List<PortfolioPosition> positions = p.getPositions();

                    Map<String, Double> prices =
                            fetchLatestPrices(positions);
                    double total =
                            computeTotalValue(positions, prices);

                    // Fetch 30d bars for volatility, sharpe, sparkline
                    LocalDate to = TradingUtil.lastCompletedTradingDay();
                    LocalDate from = to.minusDays(30);
                    Map<String, List<PriceBar>> bars =
                            fetchBars(positions, from, to);

                    Map<LocalDate, Double> valueByDate =
                            DataUtil.buildValueSeries(positions, bars);
                    valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

                    List<Double> values = new ArrayList<>(valueByDate.values());
                    log.debug("fetchBars tickers={} from={} to={}",
                            positions.stream().map(position -> position.getAsset().getTicker()).toList(), from, to);
                    List<Double> returns =
                            buildDailyReturns(positions, bars);

                    double vol = returns.size() >= 2
                            ? riskPort.annualisedVolatility(returns) : 0.0;
                    double sharpe = returns.size() >= 2
                            ? riskPort.sharpeRatio(returns) : 0.0;
                    List<Double> spark = SeriesUtil.downsample(values, 7);

                    String createdAt = p.getCreatedAt() != null
                            ? p.getCreatedAt().toString() : null;

                    return new PortfolioDto(
                            p.getId().toString(),
                            p.getName(),
                            total,
                            createdAt,
                            positions.size(),
                            vol,
                            sharpe,
                            spark
                    );
                })
                .toList();
    }

    // ── Portfolio ──────────────────────────────────────────────────────────────

    /**
     * Returns a single portfolio overview enriched with valuation and trend metrics.
     */
    public PortfolioDto getPortfolio(UUID id, UUID principalId) {
        log.debug("Fetching portfolio with id={}", id);

        return portfolioRepository.findByIdWithPositions(id)
                .map(portfolio -> {
                    if (!portfolio.getUserId().equals(principalId)) {
                        throw new AccessDeniedException("Access denied to portfolio: " + id);
                    }

                    List<PortfolioPosition> positions = portfolio.getPositions();

                    Map<String, Double> prices =
                            fetchLatestPrices(positions);
                    double total =
                            computeTotalValue(positions, prices);

                    LocalDate to = TradingUtil.lastCompletedTradingDay();
                    LocalDate from = to.minusDays(30);
                    Map<String, List<PriceBar>> bars =
                            fetchBars(positions, from, to);

                    Map<LocalDate, Double> valueByDate =
                            DataUtil.buildValueSeries(positions, bars);
                    valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

                    List<Double> values = new ArrayList<>(valueByDate.values());
                    log.debug("fetchBars tickers={} from={} to={}",
                            positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
                    List<Double> returns =
                            buildDailyReturns(positions, bars);

                    double vol = returns.size() >= 2
                            ? riskPort.annualisedVolatility(returns) : 0.0;
                    double sharpe = returns.size() >= 2
                            ? riskPort.sharpeRatio(returns) : 0.0;
                    List<Double> spark = SeriesUtil.downsample(values, 7);

                    String createdAt = portfolio.getCreatedAt() != null
                            ? portfolio.getCreatedAt().toString() : null;

                    return new PortfolioDto(
                            id.toString(),
                            portfolio.getName(),
                            total,
                            createdAt,
                            positions.size(),
                            vol,
                            sharpe,
                            spark
                    );
                }).orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + id));
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    /**
     * Produces headline KPI metrics for a selected date range in the dashboard.
     */
    public PortfolioSummaryDto getSummary(UUID id, String range, UUID principalId) {
        log.debug("Building summary portfolioId={} range={}", id, range);

        Portfolio portfolio = loadPortfolioWithPositions(id, principalId);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) return PortfolioSummaryDto.empty();

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                DataUtil.buildValueSeries(positions, barsByTicker);

        // Remove zero-value dates — these are holidays or corrupt bars where
        // no position had valid price data, and would distort return calculations.
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> portfolioValues = new ArrayList<>(valueByDate.values());
        log.debug("fetchBars tickers={} from={} to={}",
                positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
        List<Double> dailyReturns =
                buildDailyReturns(positions, barsByTicker);

        if (portfolioValues.isEmpty() || dailyReturns.isEmpty()) return PortfolioSummaryDto.empty();

        double currentValue = DataUtil.lastOrZero(portfolioValues);
        double prevValue = DataUtil.previousOrCurrent(portfolioValues);
        double dailyReturn = currentValue - prevValue;
        double startValue = DataUtil.firstOrZero(portfolioValues);
        double totalDeltaPct = DataUtil.safePct(startValue, currentValue - startValue);

        double vol = riskPort.annualisedVolatility(dailyReturns);
        double sharpe = riskPort.sharpeRatio(dailyReturns);
        // Calculate daily VaR percentage
        double var95 = riskPort.var95(currentValue, dailyReturns);

        // Delta metrics — compare second half of period against first half.
        int mid = dailyReturns.size() / 2;
        double dailyReturnDeltaPct = 0.0;
        double volDelta = 0.0;
        double sharpeDelta = 0.0;
        double varDelta = 0.0;
        if (mid > 0) {
            List<Double> firstHalf = dailyReturns.subList(0, mid);
            List<Double> secondHalf = dailyReturns.subList(mid, dailyReturns.size());
            double firstHalfAvg = firstHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double secondHalfAvg = secondHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            dailyReturnDeltaPct = (secondHalfAvg - firstHalfAvg) * 100.0;
            volDelta = riskPort.annualisedVolatility(secondHalf)
                    - riskPort.annualisedVolatility(firstHalf);
            sharpeDelta = riskPort.sharpeRatio(secondHalf)
                    - riskPort.sharpeRatio(firstHalf);
            varDelta = riskPort.var95(currentValue, secondHalf)
                    - riskPort.var95(currentValue, firstHalf);
        }

        List<Double> spark = SeriesUtil.downsample(portfolioValues, 7);
        List<Double> returnSpark = SeriesUtil.downsample(dailyReturns, 7);
        List<Double> volSpark = SeriesUtil.downsample(
                riskPort.rollingVolatilityFromReturns(dailyReturns, 20), 7);

        // buildRollingMetric slices must be returns, not raw values — pass
        // dailyReturns instead of portfolioValues so the lambda receives
        // pre-converted percentage changes without needing toDailyReturns().
        List<Double> sharpeSpark = dailyReturns.size() >= MIN_RETURNS_FOR_METRICS
                ? buildRollingMetric(dailyReturns, 7, riskPort::sharpeRatio)
                : List.of();

        List<Double> varSpark = dailyReturns.size() >= MIN_RETURNS_FOR_METRICS
                ? buildRollingMetric(dailyReturns, 7, slice -> riskPort.var95(currentValue, slice))
                : List.of();
        SparklineDto sparkline = new SparklineDto(spark, returnSpark, volSpark, sharpeSpark, varSpark);

        return new PortfolioSummaryDto(
                currentValue, totalDeltaPct,
                dailyReturn, dailyReturnDeltaPct,
                vol, volDelta,
                sharpe, sharpeDelta,
                var95, varDelta,
                sparkline
        );
    }

    // ── Positions ────────────────────────────────────────────────────────────

    /**
     * Returns per-position analytics including value, change, and contribution metrics.
     */
    public List<PositionDto> getPositions(UUID id, UUID principalId) {
        Portfolio portfolio = loadPortfolioWithPositions(id, principalId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.isEmpty()) return List.of();

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = to.minusDays(30);

        Map<String, List<PriceBar>> barsByTicker = positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> marketDataPort.getHistoricalBars(ticker, from, to)
                ));

        // Need total value upfront for weight and volatilityContribution
        Map<String, Double> latestPrices = barsByTicker.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getLast().getAdjClose().doubleValue()
                ));

        double totalValue = positions.stream()
                .mapToDouble(pos -> {
                    double price = latestPrices.getOrDefault(pos.getAsset().getTicker(), 0.0);
                    return price * pos.getQuantity().doubleValue();
                })
                .sum();

        // Per-position volatility needed for weighted contribution
        Map<String, Double> volByTicker = barsByTicker.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> riskPort.annualisedVolatility(DataUtil.toReturnsFromBars(e.getValue()))
                ));

        return positions.stream().map(pos -> {
            String ticker = pos.getAsset().getTicker();
            List<PriceBar> bars = barsByTicker.getOrDefault(ticker, List.of());

            if (bars.isEmpty()) {
                return PositionDto.empty(pos, ticker, 0.0);
            }

            PriceBar latest = bars.getLast();
            PriceBar prev = bars.size() > 1 ? bars.get(bars.size() - 2) : latest;

            double price = latest.getAdjClose().doubleValue();
            double change = latest.dailyReturn(prev) * prev.getAdjClose().doubleValue();
            double changePct = latest.dailyReturn(prev) * 100;
            double posValue = price * pos.getQuantity().doubleValue();

            double weight = totalValue > 0 ? (posValue / totalValue) * 100 : 0.0;
            double vol = volByTicker.getOrDefault(ticker, 0.0);
            // Weighted volatility contribution: weight% × annualised vol
            double volContribution = weight * vol / 100.0;

            int score = riskPort.riskScore(pos.getAsset().getType().name(), vol);
            String level = riskPort.riskLevel(score);

            return new PositionDto(
                    pos.getId().toString(),
                    ticker,
                    pos.getAsset().getName(),
                    pos.getAsset().getType().name(),
                    price,
                    change,
                    changePct,
                    score,
                    level,
                    SeriesUtil.formatVolume(latest.getVolume()),
                    SeriesUtil.formatPositionValue(posValue),
                    pos.getAsset().getSector().getLabel(),
                    weight,
                    vol,
                    volContribution,
                    pos.getQuantity().doubleValue(),
                    pos.getAvgBuyPrice() != null ? pos.getAvgBuyPrice().doubleValue() : price,
                    price,
                    posValue,
                    changePct,
                    volContribution
            );
        }).toList();
    }

    // ── Composition ──────────────────────────────────────────────────────────

    /**
     * Computes sector allocation percentages for portfolio composition charts.
     */
    public List<CompositionEntryDto> getComposition(UUID id, UUID principalId) {
        log.debug("Building composition portfolioId={}", id);

        Portfolio portfolio = loadPortfolioWithPositions(id, principalId);

        // Fetch all prices once up front rather than once per position in forEach.
        Map<String, Double> priceByTicker = portfolio.getPositions().stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> marketDataPort.getLatestPrice(ticker).doubleValue()
                ));

        Map<Sector, Double> valuesBySector = new LinkedHashMap<>();

        portfolio.getPositions().forEach(pos -> {
            double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
            double posValue = price * pos.getQuantity().doubleValue();
            valuesBySector.merge(pos.getAsset().getSector(), posValue, Double::sum);
        });

        double totalValue = valuesBySector.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();

        if (totalValue == 0.0) return List.of();

        return valuesBySector.entrySet().stream()
                .map(e -> new CompositionEntryDto(
                        e.getKey().getLabel(),
                        (e.getValue() / totalValue) * 100,
                        e.getKey().getColor()
                ))
                .sorted(Comparator.comparingDouble(CompositionEntryDto::value).reversed())
                .toList();
    }

    // ── Volatility ───────────────────────────────────────────────────────────

    /**
     * Computes rolling volatility points for the selected analysis range.
     */
    public List<VolatilityPointDto> getVolatility(UUID id, String range, UUID principalId) {
        log.debug("Calculating volatility portfolioId={} range={}", id, range);

        Portfolio portfolio = loadPortfolioWithPositions(id, principalId);

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                fetchBars(portfolio.getPositions(), from, to);
        Map<LocalDate, Double> valueByDate =
                DataUtil.buildValueSeries(portfolio.getPositions(), barsByTicker);

        // Remove zero-value dates before computing returns — same as getSummary.
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        List<LocalDate> dates = new ArrayList<>(valueByDate.keySet());

        List<Double> rollingVol = riskPort.rollingVolatility(values, 20);  // let rollingVolatility handle conversion

        // rollingVol is shorter than dailyReturns by (window - 1) = 19 bars.
        // Align to the tail of the dates list so each point maps to the correct date.
        int offset = dates.size() - rollingVol.size();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

        List<VolatilityPointDto> result = new ArrayList<>(rollingVol.size());
        for (int i = 0; i < rollingVol.size(); i++) {
            Double vol = rollingVol.get(i);
            if (vol == null) continue;  // skip points where window was too small
            result.add(new VolatilityPointDto(
                    dates.get(offset + i).format(fmt),
                    vol
            ));
        }

        return result;
    }

    // ── Alerts ───────────────────────────────────────────────────────────────

    /**
     * Generates user-facing risk alerts derived from concentration and exposure checks.
     */
    public List<RiskAlertDto> getAlerts(UUID id, UUID principalId) {
        log.debug("Generating risk alerts portfolioId={}", id);

        Portfolio portfolio = loadPortfolioWithPositions(id, principalId);

        // Single price fetch pass — reused for both total value and sector weights.
        Map<String, Double> priceByTicker = portfolio.getPositions().stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> marketDataPort.getLatestPrice(ticker).doubleValue()
                ));

        double totalValue = portfolio.getPositions().stream()
                .mapToDouble(pos -> {
                    double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
                    return price * pos.getQuantity().doubleValue();
                })
                .sum();

        if (totalValue == 0.0) return List.of();

        Map<String, Double> sectorWeights = new LinkedHashMap<>();

        portfolio.getPositions().forEach(pos -> {
            double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
            double weight = (price * pos.getQuantity().doubleValue() / totalValue) * 100;
            sectorWeights.merge(pos.getAsset().getSector().getLabel(), weight, Double::sum);
        });

        log.trace("Sector weights: {}", sectorWeights);

        List<RiskAlertDto> alerts = new ArrayList<>();

        // Sector concentration alerts
        sectorWeights.forEach((sector, weight) -> {
            if (weight > 40) {
                List<String> affected = portfolio.getPositions().stream()
                        .filter(pos -> pos.getAsset().getSector().getLabel().equals(sector))
                        .map(pos -> pos.getAsset().getTicker())
                        .toList();

                alerts.add(new RiskAlertDto(
                        UUID.randomUUID().toString(),
                        "Concentration in %s sector reached %.0f%%".formatted(sector, weight),
                        "high",
                        "just now",
                        affected
                ));
            }
        });

        double cryptoWeight = portfolio.getPositions().stream()
                .filter(pos -> pos.getAsset().getType() == AssetType.CRYPTO)
                .mapToDouble(pos -> {
                    double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
                    return (price * pos.getQuantity().doubleValue() / totalValue) * 100;
                })
                .sum();

        if (cryptoWeight > 10) {
            List<String> cryptoTickers = portfolio.getPositions().stream()
                    .filter(pos -> pos.getAsset().getType() == AssetType.CRYPTO)
                    .map(pos -> pos.getAsset().getTicker())
                    .toList();

            alerts.add(new RiskAlertDto(
                    UUID.randomUUID().toString(),
                    "Crypto exposure at %.0f%% exceeds 10%% threshold".formatted(cryptoWeight),
                    "moderate",
                    "recently",
                    cryptoTickers
            ));
        }

        if (alerts.isEmpty()) {
            alerts.add(new RiskAlertDto(
                    UUID.randomUUID().toString(),
                    "No active risk alerts — portfolio within normal bounds",
                    "low",
                    "now",
                    List.of()
            ));
        }

        return alerts;
    }

    // ── Risk  ──────────────────────────────────────────────────────

    /**
     * Returns medium-horizon risk metrics and their trend deltas.
     */
    public RiskMetricsDto getRiskMetrics(UUID portfolioId, String range, UUID userId) {
        log.debug("Building risk metrics portfolioId={} range={}", portfolioId, range);

        Portfolio portfolio = loadPortfolioWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.isEmpty()) return RiskMetricsDto.empty();

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                DataUtil.buildValueSeries(positions, barsByTicker);
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        log.debug("fetchBars tickers={} from={} to={}",
                positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
        List<Double> returns = buildDailyReturns(positions, barsByTicker);

        if (values.size() < 2 || returns.isEmpty()) return RiskMetricsDto.empty();

        double maxDrawdown = riskPort.maxDrawdown(values);
        double concentration = riskPort.computeHHI(positions, barsByTicker);

        // Beta stubbed until benchmark series is wired — returns neutral 1.0
        double beta = 1.0;

        // Deltas — same half-split pattern as getSummary
        int mid = returns.size() / 2;
        double betaDelta = 0.0;
        double maxDrawdownDelta = 0.0;
        double concentrationDelta = 0.0;

        if (mid > 0) {
            List<Double> firstValues = values.subList(0, mid + 1);
            List<Double> secondValues = values.subList(mid, values.size());

            maxDrawdownDelta = riskPort.maxDrawdown(secondValues)
                    - riskPort.maxDrawdown(firstValues);

            // Concentration doesn't change over sub-periods (it's a snapshot),
            // so delta is 0 until you store historical snapshots — leave as 0.0
        }

        // Sparklines — 7-point rolling windows
        List<Double> drawdownSpark = buildRollingMetric(values, 7, riskPort::maxDrawdown);
        List<Double> betaSpark = Collections.nCopies(7, 1.0); // stubbed
        List<Double> concSpark = Collections.nCopies(7, concentration);

        return new RiskMetricsDto(
                beta, betaDelta,
                maxDrawdown, maxDrawdownDelta,
                concentration, concentrationDelta,
                drawdownSpark, betaSpark, concSpark
        );
    }

    /**
     * Builds a VaR report with portfolio-level, per-asset, and distribution breakdowns.
     */
    public VaRReportDto getVaRReport(UUID portfolioId, double confidenceLevel,
                                     int timeHorizonDays, UUID userId) {
        log.debug("Building VaR report portfolioId={} cl={} horizon={}",
                portfolioId, confidenceLevel, timeHorizonDays);

        Portfolio portfolio = loadPortfolioWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.isEmpty()) return VaRReportDto.empty(confidenceLevel, timeHorizonDays);

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = to.minusDays(60); // enough history for distribution + sparkline

        Map<String, List<PriceBar>> barsByTicker =
                fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                DataUtil.buildValueSeries(positions, barsByTicker);
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> portfolioValues = new ArrayList<>(valueByDate.values());
        List<Double> dailyReturns =
                buildDailyReturns(positions, barsByTicker);

        if (portfolioValues.isEmpty() || dailyReturns.isEmpty())
            return VaRReportDto.empty(confidenceLevel, timeHorizonDays);

        double currentValue = DataUtil.lastOrZero(portfolioValues);
        double vol = riskPort.annualisedVolatility(dailyReturns);

        // Z-score lookup
        double z = switch ((int) Math.round(confidenceLevel * 100)) {
            case 90 -> 1.282;
            case 99 -> 2.326;
            default -> 1.645; // 95
        };

        // Daily vol from annualised — σ_daily = σ_annual / √252
        double dailyVol = vol / Math.sqrt(252);
        // Scale to horizon: σ_T = σ_daily × √T
        double horizonVol = dailyVol * Math.sqrt(timeHorizonDays);
        double meanDailyReturn = dailyReturns.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Parametric VaR (negative = loss)
        double varPct = meanDailyReturn - z * horizonVol;
        double valueAtRisk = varPct * currentValue;

        // ── Per-asset breakdown ───────────────────────────────────────────────
        Map<String, Double> latestPrices = barsByTicker.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getLast().getAdjClose().doubleValue()
                ));

        double totalValue = positions.stream()
                .mapToDouble(p -> latestPrices.getOrDefault(p.getAsset().getTicker(), 0.0)
                        * p.getQuantity().doubleValue())
                .sum();

        // Individual VaR per asset uses same parametric formula with asset vol
        List<VaRReportDto.AssetVaRDto> assetBreakdown = positions.stream().map(pos -> {
            String ticker = pos.getAsset().getTicker();
            List<PriceBar> bars = barsByTicker.getOrDefault(ticker, List.of());
            double posValue = latestPrices.getOrDefault(ticker, 0.0)
                    * pos.getQuantity().doubleValue();
            double weight = totalValue > 0 ? (posValue / totalValue) * 100 : 0.0;

            if (bars.size() < 2) {
                return new VaRReportDto.AssetVaRDto(ticker, weight, 0, 0, 0);
            }

            List<Double> assetReturns = DataUtil.toReturnsFromBars(bars);
            double assetVol = riskPort.annualisedVolatility(assetReturns);
            double assetDailyVol = assetVol / Math.sqrt(252);
            double assetHorizonVol = assetDailyVol * Math.sqrt(timeHorizonDays);
            double assetMean = assetReturns.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);
            double assetVarPct = assetMean - z * assetHorizonVol;
            double individualVar = assetVarPct * posValue;

            // Contribution = weight-scaled share of portfolio VaR
            double contribution = (weight / 100.0) * valueAtRisk;

            return new VaRReportDto.AssetVaRDto(ticker, weight, individualVar, contribution, 0.0);
        }).toList();

        // Back-fill percentOfTotal now that we have all contributions
        double totalContribution = assetBreakdown.stream()
                .mapToDouble(a -> Math.abs(a.contribution())).sum();

        List<VaRReportDto.AssetVaRDto> assetBreakdownWithPct = assetBreakdown.stream()
                .map(a -> new VaRReportDto.AssetVaRDto(
                        a.ticker(), a.weight(), a.individualVar(), a.contribution(),
                        totalContribution > 0
                                ? (Math.abs(a.contribution()) / totalContribution) * 100 : 0.0
                )).toList();

        // ── Historical sparkline — 30-point rolling VaR ───────────────────────
        // Use last 30 values from portfolioValues; for each point compute VaR
        // over a trailing 20-bar window so each point is a real VaR estimate
        List<Double> spark = new ArrayList<>();
        int sparkPoints = Math.min(30, portfolioValues.size());
        int startIdx = portfolioValues.size() - sparkPoints;

        for (int i = startIdx; i < portfolioValues.size(); i++) {
            int windowStart = Math.max(0, i - 20);
            // Need at least 2 returns: returns are between bars, so need i > windowStart
            if (i <= windowStart) {
                spark.add(Math.abs(valueAtRisk)); // fallback
                continue;
            }
            List<Double> windowValues = portfolioValues.subList(windowStart, i + 1);
            List<Double> windowReturns = new ArrayList<>();
            for (int j = 1; j < windowValues.size(); j++) {
                double prev = windowValues.get(j - 1);
                if (prev != 0) windowReturns.add((windowValues.get(j) - prev) / prev);
            }
            if (windowReturns.size() < 2) {
                spark.add(Math.abs(valueAtRisk));
                continue;
            }
            double windowVar = riskPort.var95(portfolioValues.get(i), windowReturns);
            spark.add(Math.abs(windowVar));
        }

        // ── Return distribution — bucket daily returns into 2% bands ─────────
        double[] breakpoints = {-0.10, -0.08, -0.06, -0.04, -0.02, 0.00, 0.02, 0.04, 0.06, 0.08, 0.10};
        String[] labels = {
                "-10% to -8%", "-8% to -6%", "-6% to -4%", "-4% to -2%", "-2% to 0%",
                "0% to 2%", "2% to 4%", "4% to 6%", "6% to 8%", "8% to 10%"
        };
        int[] counts = new int[labels.length];

        for (double r : dailyReturns) {
            for (int i = 0; i < breakpoints.length - 1; i++) {
                if (r >= breakpoints[i] && r < breakpoints[i + 1]) {
                    counts[i]++;
                    break;
                }
            }
        }

        List<VaRReportDto.ReturnBucketDto> distribution = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            distribution.add(new VaRReportDto.ReturnBucketDto(labels[i], counts[i], i < 5));
        }

        VaRReportDto.MethodologyDto methodology = new VaRReportDto.MethodologyDto(
                "VaR = μ + Z × σ",
                vol * 100,       // as percentage
                z,
                meanDailyReturn * 100
        );

        return new VaRReportDto(
                valueAtRisk,
                confidenceLevel,
                timeHorizonDays,
                methodology,
                spark,
                assetBreakdownWithPct,
                distribution
        );
    }

    /**
     * Calculates an asset return correlation matrix for the selected range.
     */
    public CorrelationMatrixDto getCorrelationMatrix(UUID portfolioId, String range, UUID userId) {
        log.debug("Building correlation matrix portfolioId={} range={}", portfolioId, range);

        Portfolio portfolio = loadPortfolioWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.size() < 2) {
            // Can't correlate a single asset
            List<String> tickers = positions.stream()
                    .map(p -> p.getAsset().getTicker()).toList();
            return new CorrelationMatrixDto(tickers, List.of(List.of(1.0)));
        }

        LocalDate to = TradingUtil.lastCompletedTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        List<String> tickers = positions.stream()
                .map(p -> p.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, List<Double>> returnsByTicker = tickers.stream()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> DataUtil.toReturnsFromBars(
                                marketDataPort.getHistoricalBars(ticker, from, to))
                ));

        List<List<Double>> matrix = new ArrayList<>();
        for (String row : tickers) {
            List<Double> matrixRow = new ArrayList<>();
            for (String col : tickers) {
                if (row.equals(col)) {
                    matrixRow.add(1.0);
                } else {
                    matrixRow.add(riskPort.pearsonCorrelation(
                            returnsByTicker.get(row),
                            returnsByTicker.get(col)
                    ));
                }
            }
            matrix.add(matrixRow);
        }

        return new CorrelationMatrixDto(tickers, matrix);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<Double> buildRollingMetric(
            List<Double> values, int points, Function<List<Double>, Double> metric) {

        if (values.size() < 2) return Collections.nCopies(points, null);

        List<Double> result = new ArrayList<>(points);
        double step = (double) (values.size() - 1) / (points - 1);

        for (int i = 0; i < points; i++) {
            int end = Math.min((int) Math.round(i * step) + 1, values.size());
            int start = Math.max(0, end - 30);
            List<Double> slice = values.subList(start, end);
            result.add(slice.size() < 2 ? null : metric.apply(slice));
        }
        return result;
    }

    private Map<String, Double> fetchLatestPrices(List<PortfolioPosition> positions) {
        return positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> {
                            try {
                                return marketDataPort.getLatestPrice(ticker).doubleValue();
                            } catch (Exception e) {
                                log.error("Failed to fetch latest price ticker={}", ticker, e);
                                return 0.0;
                            }
                        }
                ));
    }

    private Map<String, List<PriceBar>> fetchBars(
            List<PortfolioPosition> positions, LocalDate from, LocalDate to) {

        Map<String, List<PriceBar>> result = new LinkedHashMap<>();

        for (PortfolioPosition pos : positions) {
            String ticker = pos.getAsset().getTicker();
            if (result.containsKey(ticker)) continue; // deduplicate

            try {
                List<PriceBar> bars = marketDataPort.getHistoricalBars(ticker, from, to);
                log.info("fetchBars ticker={} bars.size()={}", ticker, bars == null ? "null" : bars.size());
                if (bars != null && !bars.isEmpty()) {
                    result.put(ticker, bars);
                } else {
                    log.warn("No bars returned for ticker={} range={} - {}", ticker, from, to);
                }
            } catch (Exception e) {
                log.error("Failed to fetch bars ticker={} range={} - {}", ticker, from, to, e);
            }
        }

        return result;
    }

    // ── Total Value ──────────────────────────────────────────────────────────

    private double computeTotalValue(
            List<PortfolioPosition> positions, Map<String, Double> priceByTicker) {

        return positions.stream().mapToDouble(pos -> {
            double price = priceByTicker.getOrDefault(pos.getAsset().getTicker(), 0.0);
            return price * pos.getQuantity().doubleValue();
        }).sum();
    }

    // ── Daily Returns ────────────────────────────────────────────────────────

    private List<Double> buildDailyReturns(
            List<PortfolioPosition> positions,
            Map<String, List<PriceBar>> barsByTicker) {
        log.debug("buildDailyReturns tickers={} barCounts={}",
                barsByTicker.keySet(),
                barsByTicker.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
        if (barsByTicker.isEmpty()) {
            log.warn("No bar data available for return calculation");
            return List.of();
        }

        // Build per-ticker date→bar lookup for O(1) date access.
        Map<String, Map<LocalDate, PriceBar>> barMapByTicker = new LinkedHashMap<>();

        for (Map.Entry<String, List<PriceBar>> entry : barsByTicker.entrySet()) {
            if (entry.getValue().size() < 2) {
                log.warn("Insufficient bars for ticker={} ({}), excluding from returns",
                        entry.getKey(), entry.getValue().size());
                continue;
            }
            Map<LocalDate, PriceBar> dateMap = new LinkedHashMap<>();
            for (PriceBar bar : entry.getValue()) {
                dateMap.put(bar.getBarDate(), bar);
            }
            barMapByTicker.put(entry.getKey(), dateMap);
        }

        if (barMapByTicker.isEmpty()) return List.of();

        // Intersection of all dates so every return is computed on the same day
        // across all tickers — eliminates cross-ticker date misalignment.
        Set<LocalDate> commonDates = null;
        for (Map<LocalDate, PriceBar> dateMap : barMapByTicker.values()) {
            if (commonDates == null) {
                commonDates = new TreeSet<>(dateMap.keySet());
            } else {
                commonDates.retainAll(dateMap.keySet());
            }
        }

        if (commonDates.size() < 2) {
            log.warn("Fewer than 2 common trading days across tickers — cannot compute returns");
            return List.of();
        }

        List<LocalDate> sortedDates = new ArrayList<>(commonDates);
        List<Double> portfolioReturns = new ArrayList<>(sortedDates.size() - 1);

        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate prevDate = sortedDates.get(i - 1);
            LocalDate currDate = sortedDates.get(i);

            double totalPrevValue = 0.0;

            // First pass: total portfolio value at t-1 for weight computation.
            for (PortfolioPosition pos : positions) {
                Map<LocalDate, PriceBar> dateMap =
                        barMapByTicker.get(pos.getAsset().getTicker());
                if (dateMap == null) continue;

                PriceBar prev = dateMap.get(prevDate);
                if (prev == null) continue;

                totalPrevValue += prev.getAdjClose().doubleValue()
                        * pos.getQuantity().doubleValue();
            }

            if (totalPrevValue == 0.0) {
                log.warn("Zero portfolio value on date={}, skipping", prevDate);
                portfolioReturns.add(0.0);
                continue;
            }

            // Second pass: weighted return for this day.
            double weightedReturn = 0.0;

            for (PortfolioPosition pos : positions) {
                Map<LocalDate, PriceBar> dateMap =
                        barMapByTicker.get(pos.getAsset().getTicker());
                if (dateMap == null) continue;

                PriceBar prev = dateMap.get(prevDate);
                PriceBar curr = dateMap.get(currDate);
                if (prev == null || curr == null) continue;

                double posWeight = (prev.getAdjClose().doubleValue()
                        * pos.getQuantity().doubleValue()) / totalPrevValue;

                weightedReturn += posWeight * curr.dailyReturn(prev);
            }

            portfolioReturns.add(weightedReturn);
        }

        return portfolioReturns;
    }
}