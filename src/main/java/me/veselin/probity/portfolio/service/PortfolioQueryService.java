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
    private final PortfolioRepository portfolioRepository;
    private final MarketDataPort marketDataPort;
    private final RiskPort riskPort;
    private final PortfolioValuationService portfolioValuationService;

    // ── Portfolios list ──────────────────────────────────────────────────────

    public List<PortfolioDto> getPortfolios(UUID userId) {
        log.debug("Fetching portfolios for userId={}", userId);

        return portfolioRepository.findByUserIdWithPositions(userId).stream()
                .map(p -> {
                    double total = portfolioValuationService.computeTotalValue(p);
                    return new PortfolioDto(p.getId().toString(), p.getName(), total);
                })
                .toList();
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    public PortfolioSummaryDto getSummary(String id, String range) {
        log.debug("Building summary for portfolioId={}, range={}", id, range);

        Portfolio portfolio = loadWithPositions(id);
        List<PortfolioPosition> positions = portfolio.getPositions();

        log.debug("Portfolio {} has {} positions", id, positions.size());

        if (positions.isEmpty()) return emptyPortfolioSummary();

        LocalDate to = LocalDate.now();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(positions, from, to);

        List<Double> portfolioValues = new ArrayList<>(valueByDate.values());
        List<Double> dailyReturns =
                portfolioValuationService.buildDailyReturns(positions, from, to);

        double currentValue = lastOrZero(portfolioValues);
        double prevValue = previousOrCurrent(portfolioValues);
        double dailyReturn = currentValue - prevValue;
        double dailyReturnPct = safePct(prevValue, dailyReturn);
        double startValue = firstOrZero(portfolioValues);
        double totalDeltaPct = safePct(startValue, currentValue - startValue);

        double vol = riskPort.annualisedVolatility(dailyReturns);
        double sharpe = riskPort.sharpeRatio(dailyReturns);
        double var95 = riskPort.var95(currentValue, dailyReturns);

        List<Double> spark = SeriesUtil.downsample(portfolioValues, 7);
        List<Double> volSpark =
                SeriesUtil.downsample(riskPort.rollingVolatility(portfolioValues, 20), 7);

        List<Double> sharpeSpark = buildRollingMetric(portfolioValues, 7,
                slice -> riskPort.sharpeRatio(riskPort.toDailyReturns(slice)));

        List<Double> varSpark = buildRollingMetric(portfolioValues, 7,
                slice -> riskPort.var95(lastOrZero(slice), riskPort.toDailyReturns(slice)));

        SparklineDto sparkline =
                new SparklineDto(spark, spark, volSpark, sharpeSpark, varSpark);

        return new PortfolioSummaryDto(
                currentValue, totalDeltaPct,
                dailyReturn, dailyReturnPct,
                vol, 0.0,
                sharpe, 0.0,
                var95, 0.0,
                sparkline
        );
    }

    // ── Positions ────────────────────────────────────────────────────────────

    public List<PositionDto> getPositions(String id) {
        log.debug("Fetching positions for portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);

        log.debug("Fetching latest prices for {} positions", portfolio.getPositions().size());

        Map<String, Double> latestPrices = portfolio.getPositions().stream()
                .collect(Collectors.toMap(
                        pos -> pos.getAsset().getTicker(),
                        pos -> marketDataPort.getLatestPrice(pos.getAsset().getTicker()).doubleValue(),
                        (a, b) -> a // prevent duplicate ticker crash
                ));

        return portfolio.getPositions().stream().map(pos -> {
            String ticker = pos.getAsset().getTicker();
            log.trace("Processing position ticker={}", ticker);

            List<PriceBar> bars = marketDataPort.getHistoricalBars(ticker, from, to);

            if (bars == null || bars.isEmpty()) {
                log.warn("No historical data for ticker={} in range {} - {}", ticker, from, to);

                double posValue = latestPrices.getOrDefault(ticker, 0.0)
                        * pos.getQuantity().doubleValue();

                return emptyPositionDto(pos, ticker, posValue);
            }

            log.trace("Bars fetched for ticker={} count={}", ticker, bars.size());

            List<Double> returns = toReturnsFromBars(bars);
            double vol = riskPort.annualisedVolatility(returns);
            int score = riskPort.riskScore(pos.getAsset().getType().name(), vol);
            String level = riskPort.riskLevel(score);

            PriceBar latest = bars.getLast();
            PriceBar prev = bars.size() > 1 ? bars.get(bars.size() - 2) : latest;

            double price = latest.getAdjClose().doubleValue();
            double change = latest.dailyReturn(prev) * prev.getAdjClose().doubleValue();
            double changePct = latest.dailyReturn(prev) * 100;

            double posValue = latestPrices.get(ticker)
                    * pos.getQuantity().doubleValue();

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
                    SeriesUtil.formatMarketCap(posValue),
                    pos.getAsset().getSector().getLabel()
            );
        }).toList();
    }

    // ── Composition ──────────────────────────────────────────────────────────

    public List<CompositionEntryDto> getComposition(String id) {
        log.debug("Building composition for portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);
        Map<Sector, Double> valuesBySector = new LinkedHashMap<>();
        double totalValue = portfolioValuationService.computeTotalValue(portfolio);

        portfolio.getPositions().forEach(pos -> {
            double price = marketDataPort.getLatestPrice(pos.getAsset().getTicker()).doubleValue();
            double posValue = price * pos.getQuantity().doubleValue();
            valuesBySector.merge(pos.getAsset().getSector(), posValue, Double::sum);
        });

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
        log.debug("Calculating volatility for portfolioId={}, range={}", id, range);

        Portfolio portfolio = loadWithPositions(id);

        LocalDate to = LocalDate.now();
        LocalDate from = DateRange.fromValue(range).toStartDate(to);

        Map<LocalDate, Double> valueByDate =
                portfolioValuationService.buildValueSeries(portfolio.getPositions(), from, to);

        List<Double> rollingVol =
                riskPort.rollingVolatility(new ArrayList<>(valueByDate.values()), 20);

        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        List<LocalDate> dates = new ArrayList<>(valueByDate.keySet());

        List<VolatilityPointDto> result = new ArrayList<>(rollingVol.size());

        for (int i = 0; i < rollingVol.size(); i++) {
            String date = i < dates.size()
                    ? dates.get(i).format(fmt)
                    : to.format(fmt);

            result.add(new VolatilityPointDto(date, rollingVol.get(i)));
        }

        return result;
    }

    // ── Alerts ───────────────────────────────────────────────────────────────

    public List<RiskAlertDto> getAlerts(String id) {
        log.debug("Generating risk alerts for portfolioId={}", id);

        Portfolio portfolio = loadWithPositions(id);
        double totalValue = portfolioValuationService.computeTotalValue(portfolio);

        Map<String, Double> sectorWeights = portfolio.getPositions().stream()
                .collect(Collectors.toMap(
                        p -> p.getAsset().getSector().getLabel(),
                        p -> marketDataPort.getLatestPrice(p.getAsset().getTicker()).doubleValue()
                                * p.getQuantity().doubleValue() / totalValue * 100,
                        Double::sum,
                        LinkedHashMap::new
                ));

        log.trace("Sector weights: {}", sectorWeights);

        List<RiskAlertDto> alerts = new ArrayList<>();

        sectorWeights.forEach((sector, weight) -> {
            if (weight > 40) {
                alerts.add(new RiskAlertDto(UUID.randomUUID().toString(),
                        "Concentration in %s sector reached %.0f%%".formatted(sector, weight),
                        "high", "just now", "⚠️"));
            }
        });

        double cryptoWeight = sectorWeights.entrySet().stream()
                .filter(e -> Sector.from(e.getKey()) == Sector.CRYPTO)
                .mapToDouble(Map.Entry::getValue)
                .sum();

        if (cryptoWeight > 10) {
            alerts.add(new RiskAlertDto(UUID.randomUUID().toString(),
                    "Crypto exposure at %.0f%% exceeds 10%% threshold".formatted(cryptoWeight),
                    "moderate", "recently", "📢"));
        }

        if (alerts.isEmpty()) {
            alerts.add(new RiskAlertDto(UUID.randomUUID().toString(),
                    "No active risk alerts — portfolio within normal bounds",
                    "low", "now", "✓"));
        }

        return alerts;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Portfolio loadWithPositions(String id) {
        log.debug("Loading portfolio with positions id={}", id);

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
                0.0,
                0.0,
                0.0,
                0,
                "UNKNOWN",
                "N/A",
                SeriesUtil.formatMarketCap(posValue),
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

    private List<Double> buildRollingMetric(List<Double> values, int points, Function<List<Double>, Double> metric) {
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
        return list.isEmpty() ? 0 : list.getLast();
    }

    private double firstOrZero(List<Double> list) {
        return list.isEmpty() ? 0 : list.getFirst();
    }

    private double previousOrCurrent(List<Double> list) {
        return list.size() > 1 ? list.get(list.size() - 2) : lastOrZero(list);
    }

    private double safePct(double base, double delta) {
        return base != 0 ? (delta / base) * 100 : 0;
    }

    private PortfolioSummaryDto emptyPortfolioSummary() {
        SparklineDto empty = new SparklineDto(List.of(), List.of(), List.of(), List.of(), List.of());
        return new PortfolioSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, empty);
    }
}