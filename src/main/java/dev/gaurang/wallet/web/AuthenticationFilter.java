package dev.gaurang.wallet.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gaurang.wallet.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    /** Token issuing, the admin token endpoint and the operational endpoints authenticate their own way. */
    private static final List<String> UNAUTHENTICATED_PREFIXES =
            List.of("/auth/", "/admin/", "/health", "/metrics", "/info", "/actuator");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNAUTHENTICATED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> userId = presentedToken(request).flatMap(authService::resolveUserId);
        if (userId.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        MDC.put("user_id", userId.get());
        chain.doFilter(request, response);
    }

    private Optional<String> presentedToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER_PREFIX.length()).trim()).filter(token -> !token.isEmpty());
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorResponse body = new ErrorResponse(ErrorCode.UNAUTHENTICATED.name(),
                "Missing or invalid bearer token", MDC.get(CorrelationIdFilter.MDC_KEY));
        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
