package me.veselin.probity.portfolio.service.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.SeriesUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.enumeration.AssetType;
import me.veselin.probity.portfolio.enumeration.DateRange;
import me.veselin.probity.portfolio.enumeration.Sector;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
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
public class PortfolioQueryService {

    private static final int MIN_RETURNS_FOR_METRICS = 5;
    private final PortfolioRepository portfolioRepository;
    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;
    private final PortfolioValuationService portfolioValuationService;

    // ── Portfolios list ──────────────────────────────────────────────────────

    public List<PortfolioDto> getPortfolios(UUID userId) {
        log.debug("Fetching portfolios for userId={}", userId);

        return portfolioRepository.findByUserIdWithPositions(userId).stream()
                .map(p -> {
                    List<PortfolioPosition> positions = p.getPositions();

                    Map<String, Double> prices =
                            portfolioValuationService.fetchLatestPrices(positions);
                    double total =
                            portfolioValuationService.computeTotalValue(positions, prices);

                    // Fetch 30d bars for volatility, sharpe, sparkline
                    LocalDate to = lastTradingDay();
                    LocalDate from = to.minusDays(30);
                    Map<String, List<PriceBar>> bars =
                            portfolioValuationService.fetchBars(positions, from, to);

                    Map<LocalDate, Double> valueByDate =
                            portfolioValuationService.buildValueSeries(positions, bars);
                    valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

                    List<Double> values = new ArrayList<>(valueByDate.values());
                    log.debug("fetchBars tickers={} from={} to={}",
                            positions.stream().map(position -> position.getAsset().getTicker()).toList(), from, to);
                    List<Double> returns =
                            portfolioValuationService.buildDailyReturns(positions, bars);

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

    public PortfolioDto getPortfolio(UUID id, UUID principalId) {
        log.debug("Fetching portfolio with id={}", id);

        return portfolioRepository.findByIdWithPositions(id)
                .map(portfolio -> {
                    if (!portfolio.getUserId().equals(principalId)) {
                        throw new AccessDeniedException("Access denied to portfolio: " + id);
                    }

                    List<PortfolioPosition> positions = portfolio.getPositions();

                    Map<String, Double> prices =
                            portfolioValuationService.fetchLatestPrices(positions);
                    double total =
                            portfolioValuationService.computeTotalValue(positions, prices);

                    LocalDate to = lastTradingDay();
                    LocalDate from = to.minusDays(30);
                    Map<String, List<PriceBar>> bars =
                            portfolioValuationService.fetchBars(positions, from, to);

                    Map<LocalDate, Double> valueByDate =
                            portfolioValuationService.buildValueSeries(positions, bars);
                    valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

                    List<Double> values = new ArrayList<>(valueByDate.values());
                    log.debug("fetchBars tickers={} from={} to={}",
                            positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
                    List<Double> returns =
                            portfolioValuationService.buildDailyReturns(positions, bars);

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

    public PortfolioSummaryDto getSummary(UUID id, String range, UUID principalId) {
        log.debug("Building summary portfolioId={} range={}", id, range);

        Portfolio portfolio = loadWithPositions(id, principalId);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) return emptyPortfolioSummary();

        LocalDate to = lastTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                portfolioValuationService.fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(positions, barsByTicker);

        // Remove zero-value dates — these are holidays or corrupt bars where
        // no position had valid price data, and would distort return calculations.
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> portfolioValues = new ArrayList<>(valueByDate.values());
        log.debug("fetchBars tickers={} from={} to={}",
                positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
        List<Double> dailyReturns =
                portfolioValuationService.buildDailyReturns(positions, barsByTicker);

        if (portfolioValues.isEmpty() || dailyReturns.isEmpty()) return emptyPortfolioSummary();

        double currentValue = lastOrZero(portfolioValues);
        double prevValue = previousOrCurrent(portfolioValues);
        double dailyReturn = currentValue - prevValue;
        double startValue = firstOrZero(portfolioValues);
        double totalDeltaPct = safePct(startValue, currentValue - startValue);

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

    public List<PositionDto> getPositions(UUID id, UUID principalId) {
        Portfolio portfolio = loadWithPositions(id, principalId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.isEmpty()) return List.of();

        LocalDate to = lastTradingDay();
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
                        e -> riskPort.annualisedVolatility(toReturnsFromBars(e.getValue()))
                ));

        return positions.stream().map(pos -> {
            String ticker = pos.getAsset().getTicker();
            List<PriceBar> bars = barsByTicker.getOrDefault(ticker, List.of());

            if (bars.isEmpty()) {
                return emptyPositionDto(pos, ticker, 0.0);
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

            List<Double> returns = toReturnsFromBars(bars);
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
                    posValue
            );
        }).toList();
    }

    // ── Composition ──────────────────────────────────────────────────────────

    public List<CompositionEntryDto> getComposition(UUID id, UUID principalId) {
        log.debug("Building composition portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id, principalId);

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

    public List<VolatilityPointDto> getVolatility(UUID id, String range, UUID principalId) {
        log.debug("Calculating volatility portfolioId={} range={}", id, range);

        Portfolio portfolio = loadWithPositions(id, principalId);

        LocalDate to = lastTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(portfolio.getPositions(), from, to);

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

    public List<RiskAlertDto> getAlerts(UUID id, UUID principalId) {
        log.debug("Generating risk alerts portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id, principalId);

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

    public RiskMetricsDto getRiskMetrics(UUID portfolioId, String range, UUID userId) {
        log.debug("Building risk metrics portfolioId={} range={}", portfolioId, range);

        Portfolio portfolio = loadWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.isEmpty()) return emptyRiskMetrics();

        LocalDate to = lastTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                portfolioValuationService.fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(positions, barsByTicker);
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> values = new ArrayList<>(valueByDate.values());
        log.debug("fetchBars tickers={} from={} to={}",
                positions.stream().map(p -> p.getAsset().getTicker()).toList(), from, to);
        List<Double> returns = portfolioValuationService.buildDailyReturns(positions, barsByTicker);

        if (values.size() < 2 || returns.isEmpty()) return emptyRiskMetrics();

        double maxDrawdown = riskPort.maxDrawdown(values);
        double concentration = computeHHI(positions, barsByTicker);

        // Beta stubbed until benchmark series is wired — returns neutral 1.0
        double beta = 1.0;

        // Deltas — same half-split pattern as getSummary
        int mid = returns.size() / 2;
        double betaDelta = 0.0;
        double maxDrawdownDelta = 0.0;
        double concentrationDelta = 0.0;

        if (mid > 0) {
            List<Double> firstValues  = values.subList(0, mid + 1);
            List<Double> secondValues = values.subList(mid, values.size());

            maxDrawdownDelta = riskPort.maxDrawdown(secondValues)
                    - riskPort.maxDrawdown(firstValues);

            // Concentration doesn't change over sub-periods (it's a snapshot),
            // so delta is 0 until you store historical snapshots — leave as 0.0
        }

        // Sparklines — 7-point rolling windows
        List<Double> drawdownSpark = buildRollingMetric(values, 7, riskPort::maxDrawdown);
        List<Double> betaSpark     = Collections.nCopies(7, 1.0); // stubbed
        List<Double> concSpark     = Collections.nCopies(7, concentration);

        return new RiskMetricsDto(
                beta, betaDelta,
                maxDrawdown, maxDrawdownDelta,
                concentration, concentrationDelta,
                drawdownSpark, betaSpark, concSpark
        );
    }

    // HHI: sum of squared weights — ranges 0 (perfectly diversified) to 1 (single position)
    private double computeHHI(List<PortfolioPosition> positions,
                              Map<String, List<PriceBar>> barsByTicker) {
        Map<String, Double> latestPrices = barsByTicker.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getLast().getAdjClose().doubleValue()
                ));

        double total = positions.stream()
                .mapToDouble(p -> latestPrices.getOrDefault(p.getAsset().getTicker(), 0.0)
                        * p.getQuantity().doubleValue())
                .sum();

        if (total == 0.0) return 0.0;

        return positions.stream()
                .mapToDouble(p -> {
                    double val = latestPrices.getOrDefault(p.getAsset().getTicker(), 0.0)
                            * p.getQuantity().doubleValue();
                    double w = val / total;
                    return w * w;
                })
                .sum();
    }

    private RiskMetricsDto emptyRiskMetrics() {
        List<Double> empty = List.of();
        return new RiskMetricsDto(0, 0, 0, 0, 0, 0, empty, empty, empty);
    }

    public CorrelationMatrixDto getCorrelationMatrix(UUID portfolioId, String range, UUID userId) {
        log.debug("Building correlation matrix portfolioId={} range={}", portfolioId, range);

        Portfolio portfolio = loadWithPositions(portfolioId, userId);
        List<PortfolioPosition> positions = portfolio.getPositions();
        if (positions.size() < 2) {
            // Can't correlate a single asset
            List<String> tickers = positions.stream()
                    .map(p -> p.getAsset().getTicker()).toList();
            return new CorrelationMatrixDto(tickers, List.of(List.of(1.0)));
        }

        LocalDate to = lastTradingDay();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        List<String> tickers = positions.stream()
                .map(p -> p.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, List<Double>> returnsByTicker = tickers.stream()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> toReturnsFromBars(
                                marketDataPort.getHistoricalBars(ticker, from, to))
                ));

        List<List<Double>> matrix = new ArrayList<>();
        for (String row : tickers) {
            List<Double> matrixRow = new ArrayList<>();
            for (String col : tickers) {
                if (row.equals(col)) {
                    matrixRow.add(1.0);
                } else {
                    matrixRow.add(pearsonCorrelation(
                            returnsByTicker.get(row),
                            returnsByTicker.get(col)
                    ));
                }
            }
            matrix.add(matrixRow);
        }

        return new CorrelationMatrixDto(tickers, matrix);
    }

    private double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) return 0.0;

        double meanX = x.subList(0, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = y.subList(0, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            cov  += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        double denom = Math.sqrt(varX * varY);
        return denom == 0.0 ? 0.0 : cov / denom;
    }
    // ── Private helpers ──────────────────────────────────────────────────────

    private Portfolio loadWithPositions(UUID id, UUID principalId) {
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

    private PositionDto emptyPositionDto(PortfolioPosition pos, String ticker, double posValue) {
        return new PositionDto(
                pos.getId().toString(), ticker,
                pos.getAsset().getName(), pos.getAsset().getType().name(),
                0.0, 0.0, 0.0, 0, "UNKNOWN", "N/A",
                SeriesUtil.formatPositionValue(posValue),
                pos.getAsset().getSector().getLabel(),
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0
        );
    }

    private List<Double> toReturnsFromBars(List<PriceBar> bars) {
        if (bars.size() < 2) return List.of();

        List<Double> result = new ArrayList<>(bars.size() - 1);
        for (int i = 1; i < bars.size(); i++) {
            result.add(bars.get(i).dailyReturn(bars.get(i - 1)));
        }
        return result;
    }

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

    private double lastOrZero(List<Double> list) {
        return list.isEmpty() ? 0.0 : list.getLast();
    }

    private double firstOrZero(List<Double> list) {
        return list.isEmpty() ? 0.0 : list.getFirst();
    }

    private double previousOrCurrent(List<Double> list) {
        return list.size() > 1 ? list.get(list.size() - 2) : lastOrZero(list);
    }

    private double safePct(double base, double delta) {
        return base != 0.0 ? (delta / base) * 100 : 0.0;
    }

    private PortfolioSummaryDto emptyPortfolioSummary() {
        SparklineDto empty = new SparklineDto(
                List.of(), List.of(), List.of(), List.of(), List.of());
        return new PortfolioSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, empty);
    }

    private LocalDate lastTradingDay() {
        LocalDate date = LocalDate.now().minusDays(1);
        while (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }
}