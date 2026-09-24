package br.com.pocbank.lambda;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementReportHandlerTest {

    private static final String CSV = """
            occurredAt,transferId,direction,type,amount,fee,counterpartyAccountId
            2026-09-23T12:00:00Z,t1,CREDIT,PIX,200.00,0,bruno
            2026-09-23T12:05:00Z,t2,DEBIT,TED,105.10,5.10,carla
            2026-09-23T12:10:00Z,t3,DEBIT,PIX,50.00,0.00,bruno
            """;

    @Mock
    private S3Client s3;

    @Test
    void summarizesCsvAndWritesReportUnderReportsPrefix() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), CSV.getBytes(StandardCharsets.UTF_8)));

        String reportKey = new StatementReportHandler(s3).process("bucket", "exports/ana/1.csv");

        assertThat(reportKey).isEqualTo("reports/ana/1.json");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo("reports/ana/1.json");
    }

    @Test
    void computesTotals() {
        StatementReport report = StatementReport.fromCsv("k", CSV);

        assertThat(report.entries()).isEqualTo(3);
        assertThat(report.totalCredits()).isEqualByComparingTo("200.00");
        assertThat(report.totalDebits()).isEqualByComparingTo("155.10");
        assertThat(report.totalFees()).isEqualByComparingTo("5.10");
        assertThat(report.net()).isEqualByComparingTo("44.90");
    }

    @Test
    void ignoresObjectsOutsideExportsPrefixToAvoidTriggerLoops() {
        assertThat(new StatementReportHandler(s3).process("bucket", "reports/ana/1.json"))
                .startsWith("ignored:");
        verifyNoInteractions(s3);
    }

    @Test
    void decodesUrlEncodedKeysFromTheS3Event() {
        S3Event event = mock(S3Event.class);
        S3EventNotificationRecord record = mock(S3EventNotificationRecord.class, RETURNS_DEEP_STUBS);
        when(record.getS3().getBucket().getName()).thenReturn("bucket");
        when(record.getS3().getObject().getKey()).thenReturn("reports/conta+teste/1.json");
        when(event.getRecords()).thenReturn(List.of(record));

        List<String> result = new StatementReportHandler(s3).handleRequest(event, null);

        assertThat(result).containsExactly("ignored:reports/conta teste/1.json");
    }

    @Test
    void rejectsCsvWithoutRequiredColumns() {
        assertThatThrownBy(() -> StatementReport.fromCsv("k", "foo,bar\n1,2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction");
    }
}
