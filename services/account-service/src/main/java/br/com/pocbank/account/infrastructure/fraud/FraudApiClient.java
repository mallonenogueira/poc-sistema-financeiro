package br.com.pocbank.account.infrastructure.fraud;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.UUID;

/** Cliente declarativo (Spring Cloud OpenFeign) para o motor antifraude externo. */
@FeignClient(name = "fraud-api", url = "${integrations.fraud-api.url}")
public interface FraudApiClient {

    @PostMapping("/v1/fraud/evaluations")
    FraudApiResponse evaluate(@RequestBody FraudApiRequest request);

    record FraudApiRequest(UUID transferId, UUID sourceAccountId, UUID targetAccountId,
                           BigDecimal amount, String type) {
    }

    record FraudApiResponse(String decision, int score, String reason) {

        boolean approved() {
            return "APPROVED".equalsIgnoreCase(decision);
        }
    }
}
