package br.com.pocbank.account.infrastructure.fraud;

import br.com.pocbank.account.application.port.FraudCheckPort.FraudCheckRequest;
import br.com.pocbank.account.application.port.FraudCheckPort.FraudDecision;
import br.com.pocbank.account.application.port.FraudServiceUnavailableException;
import br.com.pocbank.account.domain.transfer.TransferType;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de integração do adapter HTTP com o antifraude simulado por WireMock:
 * valida contrato (request/response), timeouts e política fail-closed com o
 * Feign e o Circuit Breaker reais, sem banco nem Kafka.
 */
@SpringBootTest(classes = FraudCheckAdapterWireMockTest.FeignOnlyApp.class, properties = {
        "spring.cloud.openfeign.client.config.fraud-api.read-timeout=1500",
        "resilience4j.timelimiter.instances.fraud-api.timeout-duration=5s"
})
class FraudCheckAdapterWireMockTest {

    @RegisterExtension
    static WireMockExtension fraudApi = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void fraudApiUrl(DynamicPropertyRegistry registry) {
        registry.add("integrations.fraud-api.url", fraudApi::baseUrl);
    }

    @Autowired
    private FraudCheckAdapter adapter;

    private final UUID transferId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID source = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID target = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private FraudCheckRequest request(String amount) {
        return new FraudCheckRequest(transferId, source, target, new BigDecimal(amount), TransferType.PIX);
    }

    @Test
    void approvedDecisionIsMappedAndContractIsRespected() {
        fraudApi.stubFor(post("/v1/fraud/evaluations")
                .willReturn(okJson("""
                        {"decision":"APPROVED","score":12,"reason":null}
                        """)));

        FraudDecision decision = adapter.evaluate(request("150.00"));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.score()).isEqualTo(12);
        fraudApi.verify(postRequestedFor(urlEqualTo("/v1/fraud/evaluations"))
                .withRequestBody(equalToJson("""
                        {
                          "transferId": "aaaaaaaa-0000-0000-0000-000000000001",
                          "sourceAccountId": "11111111-1111-1111-1111-111111111111",
                          "targetAccountId": "22222222-2222-2222-2222-222222222222",
                          "amount": 150.00,
                          "type": "PIX"
                        }
                        """)));
    }

    @Test
    void deniedDecisionCarriesReason() {
        fraudApi.stubFor(post("/v1/fraud/evaluations")
                .willReturn(okJson("""
                        {"decision":"DENIED","score":97,"reason":"Valor acima do padrão do cliente"}
                        """)));

        FraudDecision decision = adapter.evaluate(request("50000.00"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo("Valor acima do padrão do cliente");
    }

    @Test
    void serverErrorFailsClosed() {
        fraudApi.stubFor(post("/v1/fraud/evaluations").willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter.evaluate(request("10.00")))
                .isInstanceOf(FraudServiceUnavailableException.class);
    }

    @Test
    void slowResponseTimesOutAndFailsClosed() {
        fraudApi.stubFor(post("/v1/fraud/evaluations")
                .willReturn(okJson("{\"decision\":\"APPROVED\",\"score\":1}").withFixedDelay(3_000)));

        assertThatThrownBy(() -> adapter.evaluate(request("10.00")))
                .isInstanceOf(FraudServiceUnavailableException.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, KafkaAutoConfiguration.class})
    @EnableFeignClients(clients = FraudApiClient.class)
    @Import(FraudCheckAdapter.class)
    static class FeignOnlyApp {
    }
}
