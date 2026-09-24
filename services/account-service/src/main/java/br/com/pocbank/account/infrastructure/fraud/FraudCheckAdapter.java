package br.com.pocbank.account.infrastructure.fraud;

import br.com.pocbank.account.application.port.FraudCheckPort;
import br.com.pocbank.account.application.port.FraudServiceUnavailableException;
import br.com.pocbank.account.infrastructure.fraud.FraudApiClient.FraudApiRequest;
import br.com.pocbank.account.infrastructure.fraud.FraudApiClient.FraudApiResponse;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter (hexagonal) + Anti-Corruption Layer: traduz o contrato externo para o
 * modelo do domínio e protege a aplicação com Circuit Breaker / Time Limiter
 * (Spring Cloud CircuitBreaker + Resilience4j).
 */
@Component
public class FraudCheckAdapter implements FraudCheckPort {

    static final String CIRCUIT_BREAKER = "fraud-api";

    private final FraudApiClient client;
    private final CircuitBreaker circuitBreaker;

    public FraudCheckAdapter(FraudApiClient client, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.client = client;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER);
    }

    @Override
    public FraudDecision evaluate(FraudCheckRequest request) {
        return circuitBreaker.run(() -> toDecision(client.evaluate(toApi(request))), failure -> {
            throw new FraudServiceUnavailableException(failure);
        });
    }

    private static FraudApiRequest toApi(FraudCheckRequest request) {
        return new FraudApiRequest(request.transferId(), request.sourceAccountId(), request.targetAccountId(),
                request.amount(), request.type().name());
    }

    private static FraudDecision toDecision(FraudApiResponse response) {
        return response.approved()
                ? FraudDecision.approve(response.score())
                : FraudDecision.deny(response.score(), response.reason());
    }
}
