package me.veselin.probity.marketdata.finance;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.marketdata.dto.PriceBarDto;
import me.veselin.probity.marketdata.exception.MarketDataException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Component
@RequiredArgsConstructor
public class YahooFinanceAdapter implements FinanceAdapter {

    private final RestTemplate restTemplate;

    // Yahoo Finance uses Unix timestamps for date range filtering
    private static final String BASE_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&period1=%d&period2=%d";

    // Yahoo Finance blocks requests without a browser-like User-Agent
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    public List<PriceBarDto> fetchDailyBars(String ticker, LocalDate from, LocalDate to) {
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = String.format(BASE_URL, ticker, period1, period2);

        System.out.println(url);
        try {
            JsonNode root = fetchWithHeaders(url);
            validateResponse(root, ticker);
            return extractBars(ticker, root);

        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("Failed to fetch data for ticker: " + ticker, e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private JsonNode fetchWithHeaders(String url) {
        // Yahoo Finance requires a User-Agent header or it returns 401/404
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, JsonNode.class
        );
        System.out.println(response.getBody());
        return response.getBody();
    }

    private void validateResponse(JsonNode root, String ticker) {
        if (root == null) {
            throw new MarketDataException("Received null response from Yahoo Finance for " + ticker);
        }

        JsonNode result = root.path("chart").path("result");
        JsonNode error  = root.path("chart").path("error");

        if (!error.isNull() && error.has("description")) {
            throw new MarketDataException("Yahoo Finance API error: " + error.get("description").asText());
        }
        if (result.isMissingNode() || result.isNull() || !result.isArray() || result.isEmpty()) {
            throw new MarketDataException("No data returned from Yahoo Finance for " + ticker);
        }
    }

    private List<PriceBarDto> extractBars(String ticker, JsonNode root) {
        JsonNode result    = root.path("chart").path("result").get(0);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quote     = result.path("indicators").path("quote").get(0);
        JsonNode adjClose  = result.path("indicators").path("adjclose").get(0).path("adjclose");

        List<PriceBarDto> bars = new ArrayList<>();

        for (int i = 0; i < timestamps.size(); i++) {
            // Skip any bars where Yahoo returns null (e.g. market holidays with partial data)
            if (quote.path("open").get(i).isNull()) {
                continue;
            }

            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();

            bars.add(new PriceBarDto(
                    ticker,
                    date,
                    new BigDecimal(quote.path("open").get(i).asText()),
                    new BigDecimal(quote.path("high").get(i).asText()),
                    new BigDecimal(quote.path("low").get(i).asText()),
                    new BigDecimal(quote.path("close").get(i).asText()),
                    new BigDecimal(adjClose.get(i).asText()),
                    quote.path("volume").get(i).asLong()
            ));
        }

        // Yahoo returns data chronologically already, but sort just to be safe
        bars.sort(Comparator.comparing(PriceBarDto::barDate));
        return bars;
    }
}