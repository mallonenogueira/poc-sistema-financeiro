package br.com.pocbank.account.infrastructure.config;

import br.com.pocbank.account.infrastructure.fraud.FraudApiClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fica fora da classe {@code @SpringBootApplication} de propósito: assim os testes
 * de fatia ({@code @WebMvcTest}) não sobem clientes Feign nem o agendador.
 */
@Configuration
@EnableScheduling
@EnableFeignClients(clients = FraudApiClient.class)
public class IntegrationConfig {

    @Bean
    NewTopic transferEventsTopic(@Value("${app.kafka.topics.transfer-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
