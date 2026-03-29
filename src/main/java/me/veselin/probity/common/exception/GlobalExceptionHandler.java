package me.veselin.probity.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import me.veselin.probity.auth.exception.UnauthorizedException;
import me.veselin.probity.marketdata.exception.MarketDataException;
import me.veselin.probity.portfolio.exception.PortfolioNotFoundException;
import me.veselin.probity.portfolio.exception.PositionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex,
                                              HttpServletRequest request) {
        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (first, second) -> first   // keep first message if field has multiple errors
                ));

        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", "Validation failed",
                "fields", fieldErrors,
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex,
                                              HttpServletRequest request) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", "Invalid request input",
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 401 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class, UnauthorizedException.class})
    public ResponseEntity<?> handleUnauthorized(RuntimeException ex,
                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", 401,
                "error", ex.getMessage(),
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 403 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleForbidden(Exception ex,
                                             HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", 403,
                "error", "Access denied",
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 404 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler({PortfolioNotFoundException.class, PositionNotFoundException.class})
    public ResponseEntity<?> handleNotFound(RuntimeException ex,
                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "error", ex.getMessage(),
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 409 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> handleConflict(ConflictException ex,
                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", 409,
                "error", ex.getMessage(),
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 500 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex,
                                           HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(Map.of(
                "status", 500,
                "error", ex.getMessage(),
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }

    // ── 502 ───────────────────────────────────────────────────────────────────

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<?> handleMarketData(MarketDataException ex,
                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "status", 502,
                "error", ex.getMessage(),
                "path", HtmlUtils.htmlEscape(request.getRequestURI()),
                "timestamp", Instant.now()
        ));
    }
}