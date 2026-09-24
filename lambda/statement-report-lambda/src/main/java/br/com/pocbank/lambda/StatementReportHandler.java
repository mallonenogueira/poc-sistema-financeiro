package br.com.pocbank.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Disparada por {@code s3:ObjectCreated:*} no prefixo {@code exports/}. Lê o CSV do
 * extrato, calcula totais e grava o resumo em {@code reports/...json}.
 *
 * <p>O cliente S3 é criado uma vez por container (fora do handler) para
 * reaproveitar conexões entre invocações "quentes".
 */
public class StatementReportHandler implements RequestHandler<S3Event, List<String>> {

    private static final String EXPORTS_PREFIX = "exports/";
    private static final String REPORTS_PREFIX = "reports/";

    private final S3Client s3;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StatementReportHandler() {
        // Endpoint vem de AWS_ENDPOINT_URL quando definido (LocalStack); path-style é necessário fora da AWS.
        this(S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(Boolean.parseBoolean(System.getenv("S3_FORCE_PATH_STYLE")))
                .build());
    }

    StatementReportHandler(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public List<String> handleRequest(S3Event event, Context context) {
        return event.getRecords().stream()
                .map(record -> process(record.getS3().getBucket().getName(),
                        // Chaves chegam URL-encoded no evento S3 (espaços viram '+', etc.)
                        URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8)))
                .toList();
    }

    String process(String bucket, String key) {
        if (!key.startsWith(EXPORTS_PREFIX)) {
            return "ignored:" + key; // defesa contra loop caso o trigger seja mal configurado
        }
        String csv = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asUtf8String();
        StatementReport report = StatementReport.fromCsv(key, csv);

        String reportKey = REPORTS_PREFIX + key.substring(EXPORTS_PREFIX.length()).replaceAll("\\.csv$", ".json");
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(reportKey).contentType("application/json").build(),
                RequestBody.fromString(toJson(report)));
        return reportKey;
    }

    private String toJson(StatementReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
