package br.com.pocbank.statement.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.ReceiverOptions;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

@Configuration
public class InfrastructureConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Em produção usa a cadeia padrão de credenciais (IRSA no EKS); em dev aponta para o LocalStack. */
    @Bean
    S3AsyncClient s3AsyncClient(AwsProperties aws) {
        S3AsyncClientBuilder builder = S3AsyncClient.builder().region(Region.of(aws.region()));
        String endpoint = aws.s3().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    ReceiverOptions<String, String> transferEventsReceiverOptions(
            @Value("${app.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.consumer-group}") String groupId,
            @Value("${app.kafka.topics.transfer-events}") String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return ReceiverOptions.<String, String>create(props).subscription(List.of(topic));
    }
}
