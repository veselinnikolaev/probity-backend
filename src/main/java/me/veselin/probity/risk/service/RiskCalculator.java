package me.veselin.probity.risk.service;

import me.veselin.probity.portfolio.enumeration.AssetType;
import me.veselin.probity.risk.enumeration.RiskLevel;
import me.veselin.probity.risk.port.RiskPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RiskCalculator implements RiskPort {

    @Value("${probity.risk.risk-free-rate:0.045}")
    private double riskFreeRate;   // annualised, e.g. 0.045 = 4.5%

    private static final double TRADING_DAYS = 252.0;
    private static final double Z_95         = 1.645;

    // ── RiskPort ─────────────────────────────────────────────────────────────

    @Override
    public double annualisedVolatility(List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double mean     = mean(dailyReturns);
        double variance = variance(dailyReturns, mean);
        return Math.sqrt(variance) * Math.sqrt(TRADING_DAYS) * 100; // as percentage
    }

    @Override
    public double sharpeRatio(List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double riskFreeDaily = riskFreeRate / TRADING_DAYS;
        double mean          = mean(dailyReturns);
        double stdDev        = Math.sqrt(variance(dailyReturns, mean));
        if (stdDev == 0) return 0.0;
        return ((mean - riskFreeDaily) / stdDev) * Math.sqrt(TRADING_DAYS);
    }

    @Override
    public double var95(double portfolioValue, List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) return 0.0;
        double mean    = mean(dailyReturns);
        double stdDev  = Math.sqrt(variance(dailyReturns, mean));
        return portfolioValue * Z_95 * stdDev;
    }

    @Override
    public List<Double> rollingVolatility(List<Double> priceSeries, int windowDays) {
        List<Double> returns = toDailyReturns(priceSeries);
        var result = new ArrayList<Double>(returns.size());
        for (int i = 0; i < returns.size(); i++) {
            int from  = Math.max(0, i - windowDays + 1);
            List<Double> window = returns.subList(from, i + 1);
            result.add(annualisedVolatility(window));
        }
        return result;
    }

    @Override
    public List<Double> toDailyReturns(List<Double> priceSeries) {
        if (priceSeries.size() < 2) return List.of();
        var result = new ArrayList<Double>(priceSeries.size() - 1);
        for (int i = 1; i < priceSeries.size(); i++) {
            double prev = priceSeries.get(i - 1);
            if (prev == 0) continue;
            result.add((priceSeries.get(i) - prev) / prev);
        }
        return result;
    }

    @Override
    public int riskScore(String assetType, double annualisedVol) {
        int base = AssetType.valueOf(assetType.toUpperCase()).getBaseRiskScore();
        int volAddon = (int) Math.max(0, (annualisedVol - 15) / 10 * 5);
        return Math.min(100, base + volAddon);
    }

    @Override
    public String riskLevel(int score) {
        return RiskLevel.fromScore(score).name();
    }

    // ── Math helpers — package-private so MonteCarloEngine can reuse ─────────

    double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    double variance(List<Double> values, double mean) {
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
    }

    double stdDev(List<Double> values) {
        return Math.sqrt(variance(values, mean(values)));
    }
}
