package br.com.pocbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void generatesIdWhenAbsentAndPropagatesDownstreamAndBack() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String downstream = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(downstream).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(downstream);
    }

    @Test
    void keepsIdSentByTheClient() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/accounts").header(CorrelationIdFilter.HEADER, "abc-123"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("abc-123");
    }
}
