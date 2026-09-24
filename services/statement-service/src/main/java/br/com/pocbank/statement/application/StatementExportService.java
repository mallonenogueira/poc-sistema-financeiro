package br.com.pocbank.statement.application;

import br.com.pocbank.statement.config.AwsProperties;
import br.com.pocbank.statement.domain.StatementRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Clock;

/**
 * Exporta o extrato em CSV para o S3. O upload em {@code exports/} dispara a
 * Lambda {@code statement-report} (evento S3), que gera o resumo em {@code reports/}.
 */
@Service
public class StatementExportService {

    private final StatementRepository repository;
    private final S3AsyncClient s3;
    private final AwsProperties aws;
    private final Clock clock;

    public StatementExportService(StatementRepository repository, S3AsyncClient s3, AwsProperties aws, Clock clock) {
        this.repository = repository;
        this.s3 = s3;
        this.aws = aws;
        this.clock = clock;
    }

    public Mono<ExportResult> export(String accountId) {
        return repository.findByAccountIdOrderByOccurredAtDesc(accountId)
                .collectList()
                .flatMap(entries -> {
                    String key = "exports/%s/%d.csv".formatted(accountId, clock.millis());
                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(aws.s3().bucket())
                            .key(key)
                            .contentType("text/csv")
                            .build();
                    String csv = StatementCsvWriter.write(entries);
                    return Mono.fromFuture(() -> s3.putObject(request, AsyncRequestBody.fromString(csv)))
                            .thenReturn(new ExportResult(aws.s3().bucket(), key, entries.size()));
                });
    }

    public record ExportResult(String bucket, String key, int entries) {
    }
}
