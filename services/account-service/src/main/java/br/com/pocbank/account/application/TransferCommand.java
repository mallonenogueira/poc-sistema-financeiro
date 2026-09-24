package br.com.pocbank.account.application;

import br.com.pocbank.account.domain.Amounts;
import br.com.pocbank.account.domain.transfer.InvalidTransferException;
import br.com.pocbank.account.domain.transfer.TransferType;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
                              TransferType type, String idempotencyKey) {

    public TransferCommand {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(targetAccountId, "targetAccountId");
        Objects.requireNonNull(type, "type");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException("Idempotency-Key é obrigatório");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            throw new InvalidTransferException("Conta de origem e destino devem ser diferentes");
        }
        if (!Amounts.isPositive(amount)) {
            throw new InvalidTransferException("Valor deve ser positivo");
        }
        amount = Amounts.normalize(amount);
    }
}
