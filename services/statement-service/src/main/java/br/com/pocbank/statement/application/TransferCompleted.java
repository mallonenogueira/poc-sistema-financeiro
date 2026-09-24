package br.com.pocbank.statement.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contrato consumido do tópico {@code transfer-events}. Tolerante a campos novos
 * (ignoreUnknown) para permitir evolução do produtor sem quebrar o consumidor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompleted(String transferId, String sourceAccountId, String targetAccountId,
                                BigDecimal amount, BigDecimal fee, String type, Instant occurredAt) {
}
