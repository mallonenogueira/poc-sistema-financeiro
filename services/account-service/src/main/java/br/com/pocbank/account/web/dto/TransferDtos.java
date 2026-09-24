package br.com.pocbank.account.web.dto;

import br.com.pocbank.account.domain.transfer.Transfer;
import br.com.pocbank.account.domain.transfer.TransferType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotNull TransferType type) {
    }

    public record TransferResponse(UUID id, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
                                   BigDecimal fee, String type, String status, String rejectionReason,
                                   Instant createdAt) {

        public static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getSourceAccountId(),
                    transfer.getTargetAccountId(), transfer.getAmount(), transfer.getFee(),
                    transfer.getType().name(), transfer.getStatus().name(), transfer.getRejectionReason(),
                    transfer.getCreatedAt());
        }
    }
}
