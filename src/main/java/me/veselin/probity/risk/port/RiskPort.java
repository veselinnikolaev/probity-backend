package me.veselin.probity.risk.port;

import java.util.List;

public interface RiskPort {

    /**
     * Annualised volatility as a percentage.
     * e.g. 14.2 means 14.2% annualised vol.
     */
    double annualisedVolatility(List<Double> dailyReturns);

    /**
     * Sharpe ratio annualised, using configurable risk-free rate.
     */
    double sharpeRatio(List<Double> dailyReturns);

    /**
     * Parametric VaR at 95% confidence, one-day horizon.
     * Returns absolute dollar value.
     */
    double var95(double portfolioValue, List<Double> dailyReturns);

    /**
     * Rolling annualised volatility over a sliding window.
     * Input is a price series (not returns) — conversion handled internally.
     * Returns one vol value per price point.
     */
    List<Double> rollingVolatility(List<Double> priceSeries, int windowDays);

    /**
     * Converts a price series to daily log returns.
     * Exposed here so callers don't reimplement it.
     */
    List<Double> toDailyReturns(List<Double> priceSeries);

    /**
     * Risk score 0–100 combining asset type and volatility.
     */
    int riskScore(String assetType, double annualisedVol);

    /**
     * Human-readable risk level from a score.
     */
    String riskLevel(int score);
}
