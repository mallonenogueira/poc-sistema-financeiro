package br.com.pocbank.account.web.dto;

import br.com.pocbank.account.domain.account.Account;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record OpenAccountRequest(
            @NotBlank @Size(max = 120) String holderName,
            @NotBlank @Pattern(regexp = "\\d{11}", message = "deve conter 11 dígitos (CPF)") String document,
            @NotNull @PositiveOrZero BigDecimal initialDeposit) {
    }

    public record AccountResponse(UUID id, String holderName, String maskedDocument, BigDecimal balance,
                                  String status, Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getHolderName(), mask(account.getDocument()),
                    account.getBalance(), account.getStatus().name(), account.getCreatedAt());
        }

        /** LGPD: nunca expor o CPF completo na API. */
        private static String mask(String document) {
            return "***." + document.substring(3, 6) + "." + document.substring(6, 9) + "-**";
        }
    }
}
