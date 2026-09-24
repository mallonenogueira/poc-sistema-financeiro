package br.com.pocbank.account.application.port;

import br.com.pocbank.account.domain.transfer.TransferType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Porta de saída (hexagonal): o caso de uso depende desta abstração,
 * não do cliente HTTP concreto (DIP).
 */
public interface FraudCheckPort {

    FraudDecision evaluate(FraudCheckRequest request);

    record FraudCheckRequest(UUID transferId, UUID sourceAccountId, UUID targetAccountId,
                             BigDecimal amount, TransferType type) {
    }

    record FraudDecision(boolean approved, int score, String reason) {

        public static FraudDecision approve(int score) {
            return new FraudDecision(true, score, null);
        }

        public static FraudDecision deny(int score, String reason) {
            return new FraudDecision(false, score, reason);
        }
    }
}
