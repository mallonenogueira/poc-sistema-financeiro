package br.com.pocbank.statement.application;

import br.com.pocbank.statement.config.AwsProperties;
import br.com.pocbank.statement.domain.EntryDirection;
import br.com.pocbank.statement.domain.StatementEntry;
import br.com.pocbank.statement.domain.StatementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Mock
    private StatementRepository repository;
    @Mock
    private S3AsyncClient s3;

    private final StatementEntry entry = new StatementEntry("t1:CREDIT", "ana", "t1", EntryDirection.CREDIT,
            new BigDecimal("42.00"), BigDecimal.ZERO, "bruno", "PIX", NOW);

    @Test
    void uploadsCsvUnderExportsPrefixSoTheLambdaIsTriggered() {
        when(repository.findByAccountIdOrderByOccurredAtDesc("ana")).thenReturn(Flux.just(entry));
        when(s3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));
        var service = new StatementExportService(repository, s3,
                new AwsProperties("us-east-1", new AwsProperties.S3("bucket", null)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        StepVerifier.create(service.export("ana"))
                .assertNext(result -> {
                    assertThat(result.key()).isEqualTo("exports/ana/" + NOW.toEpochMilli() + ".csv");
                    assertThat(result.entries()).isEqualTo(1);
                })
                .verifyComplete();

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(AsyncRequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().contentType()).isEqualTo("text/csv");
    }

    @Test
    void csvHasHeaderAndOneLinePerEntry() {
        String csv = StatementCsvWriter.write(List.of(entry));

        assertThat(csv.lines()).containsExactly(
                StatementCsvWriter.HEADER,
                "2026-09-23T12:00:00Z,t1,CREDIT,PIX,42.00,0,bruno");
    }
}
