package br.com.pocbank.statement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param region   região AWS
 * @param s3       bucket e, opcionalmente, endpoint alternativo (LocalStack em dev)
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(String region, S3 s3) {

    public record S3(String bucket, String endpoint) {
    }
}
