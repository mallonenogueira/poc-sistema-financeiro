package br.com.pocbank.account.infrastructure.config;

import br.com.pocbank.account.domain.fee.FeePolicy;
import br.com.pocbank.account.domain.fee.FeePolicyResolver;
import br.com.pocbank.account.domain.fee.PixFeePolicy;
import br.com.pocbank.account.domain.fee.TedFeePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Registra objetos de domínio como beans, mantendo o domínio livre de anotações do Spring. */
@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FeePolicy pixFeePolicy() {
        return new PixFeePolicy();
    }

    @Bean
    FeePolicy tedFeePolicy() {
        return new TedFeePolicy();
    }

    @Bean
    FeePolicyResolver feePolicyResolver(List<FeePolicy> policies) {
        return new FeePolicyResolver(policies);
    }
}
