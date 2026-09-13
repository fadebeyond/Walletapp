package dev.gaurang.wallet.web;

public record ErrorResponse(String code, String message, String correlationId) {
}
