package me.veselin.probity.portfolio.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.common.util.SeriesUtil;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.domain.PortfolioPosition;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.enumeration.DateRange;
import me.veselin.probity.portfolio.enumeration.Sector;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import me.veselin.probity.risk.port.RiskPort;
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
                    Map<String, Double> prices =
                            portfolioValuationService.fetchLatestPrices(p.getPositions());
                    double total =
                            portfolioValuationService.computeTotalValue(p.getPositions(), prices);
                    return new PortfolioDto(p.getId().toString(), p.getName(), total);
                })
                .toList();
    }

    // ── Portfolio ──────────────────────────────────────────────────────────────

    public PortfolioDto getPortfolio(UUID id) {
        log.debug("Fetching portfolio with id={}", id);

        return portfolioRepository.findByIdWithPositions(id)
                .map(portfolio -> {
                    Map<String, Double> prices =
                            portfolioValuationService.fetchLatestPrices(portfolio.getPositions());
                    double total =
                            portfolioValuationService.computeTotalValue(portfolio.getPositions(), prices);
                    return new PortfolioDto(id.toString(), portfolio.getName(), total);
                }).orElseThrow(() -> new PortfolioNotFoundException("Portfolio not found: " + id));
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    public PortfolioSummaryDto getSummary(String id, String range) {
        log.debug("Building summary portfolioId={} range={}", id, range);

        Portfolio portfolio = loadWithPositions(id);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) return emptyPortfolioSummary();

        LocalDate to = LocalDate.now();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<String, List<PriceBar>> barsByTicker =
                portfolioValuationService.fetchBars(positions, from, to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(positions, barsByTicker);

        // Remove zero-value dates — these are holidays or corrupt bars where
        // no position had valid price data, and would distort return calculations.
        valueByDate.entrySet().removeIf(e -> e.getValue() == 0.0);

        List<Double> portfolioValues = new ArrayList<>(valueByDate.values());
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

    public List<PositionDto> getPositions(String id) {
        log.debug("Fetching positions portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);
        List<PortfolioPosition> positions = portfolio.getPositions();

        if (positions.isEmpty()) return List.of();

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);

        // Fetch bars once per ticker — this also warms the sync cache so
        // no separate getLatestPrice call is needed. The latest bar in the
        // result is the current price.
        Map<String, List<PriceBar>> barsByTicker = positions.stream()
                .map(pos -> pos.getAsset().getTicker())
                .distinct()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> marketDataPort.getHistoricalBars(ticker, from, to)
                ));

        return positions.stream().map(pos -> {
            String ticker = pos.getAsset().getTicker();
            List<PriceBar> bars = barsByTicker.getOrDefault(ticker, List.of());

            if (bars.isEmpty()) {
                log.warn("No historical data for ticker={} range={} - {}", ticker, from, to);
                // posValue is 0 since we have no price — caller should render this as N/A.
                return emptyPositionDto(pos, ticker, 0.0);
            }

            PriceBar latest = bars.getLast();
            PriceBar prev = bars.size() > 1 ? bars.get(bars.size() - 2) : latest;

            double price = latest.getAdjClose().doubleValue();
            double change = latest.dailyReturn(prev) * prev.getAdjClose().doubleValue();
            double changePct = latest.dailyReturn(prev) * 100;
            double posValue = price * pos.getQuantity().doubleValue();

            List<Double> returns = toReturnsFromBars(bars);
            double vol = riskPort.annualisedVolatility(returns);
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
                    pos.getAsset().getSector().getLabel()
            );
        }).toList();
    }

    // ── Composition ──────────────────────────────────────────────────────────

    public List<CompositionEntryDto> getComposition(String id) {
        log.debug("Building composition portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);

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

    public List<VolatilityPointDto> getVolatility(String id, String range) {
        log.debug("Calculating volatility portfolioId={} range={}", id, range);

        Portfolio portfolio = loadWithPositions(id);

        LocalDate to = LocalDate.now();
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
            result.add(new VolatilityPointDto(
                    dates.get(offset + i).format(fmt),
                    rollingVol.get(i)
            ));
        }

        return result;
    }

    // ── Alerts ───────────────────────────────────────────────────────────────

    public List<RiskAlertDto> getAlerts(String id) {
        log.debug("Generating risk alerts portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);

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

        sectorWeights.forEach((sector, weight) -> {
            if (weight > 40) {
                alerts.add(new RiskAlertDto(
                        UUID.randomUUID().toString(),
                        "Concentration in %s sector reached %.0f%%".formatted(sector, weight),
                        "high", "just now", "warning"
                ));
            }
        });

        double cryptoWeight = sectorWeights.entrySet().stream()
                .filter(e -> Sector.from(e.getKey()) == Sector.CRYPTO)
                .mapToDouble(Map.Entry::getValue)
                .sum();

        if (cryptoWeight > 10) {
            alerts.add(new RiskAlertDto(
                    UUID.randomUUID().toString(),
                    "Crypto exposure at %.0f%% exceeds 10%% threshold".formatted(cryptoWeight),
                    "moderate", "recently", "info"
            ));
        }

        if (alerts.isEmpty()) {
            alerts.add(new RiskAlertDto(
                    UUID.randomUUID().toString(),
                    "No active risk alerts — portfolio within normal bounds",
                    "low", "now", "ok"
            ));
        }

        return alerts;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Portfolio loadWithPositions(String id) {
        return portfolioRepository.findByIdWithPositions(UUID.fromString(id))
                .orElseThrow(() -> {
                    log.error("Portfolio not found id={}", id);
                    return new PortfolioNotFoundException("Portfolio not found: " + id);
                });
    }

    private PositionDto emptyPositionDto(PortfolioPosition pos, String ticker, double posValue) {
        return new PositionDto(
                pos.getId().toString(),
                ticker,
                pos.getAsset().getName(),
                pos.getAsset().getType().name(),
                0.0, 0.0, 0.0, 0, "UNKNOWN", "N/A",
                SeriesUtil.formatPositionValue(posValue),
                pos.getAsset().getSector().getLabel()
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

        if (values.size() < 2) return Collections.nCopies(points, 0.0);

        List<Double> result = new ArrayList<>(points);
        double step = (double) (values.size() - 1) / (points - 1);

        for (int i = 0; i < points; i++) {
            int end = Math.min((int) Math.round(i * step) + 1, values.size());
            int start = Math.max(0, end - 30);
            result.add(metric.apply(values.subList(start, end)));
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
}