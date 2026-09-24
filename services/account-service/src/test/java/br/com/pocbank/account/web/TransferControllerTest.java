package br.com.pocbank.account.web;

import br.com.pocbank.account.application.TransferCommand;
import br.com.pocbank.account.application.TransferService;
import br.com.pocbank.account.application.port.FraudServiceUnavailableException;
import br.com.pocbank.account.domain.account.InsufficientFundsException;
import br.com.pocbank.account.domain.transfer.Transfer;
import br.com.pocbank.account.domain.transfer.TransferType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BODY = """
            {"sourceAccountId":"%s","targetAccountId":"%s","amount":250.00,"type":"PIX"}
            """.formatted(SOURCE, TARGET);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TransferService transferService;

    @Test
    void createsTransferAndReturnsLocation() throws Exception {
        Transfer transfer = Transfer.completed(new Transfer.Draft(UUID.randomUUID(), SOURCE, TARGET,
                new BigDecimal("250.00"), BigDecimal.ZERO, TransferType.PIX, "abc", Instant.now()));
        when(transferService.execute(any())).thenReturn(transfer);

        mvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/transfers/" + transfer.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250.00));

        ArgumentCaptor<TransferCommand> command = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService).execute(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo("abc");
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void invalidPayloadReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","amount":-1,"type":"PIX"}
                                """.formatted(SOURCE)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void insufficientFundsIsUnprocessableEntity() throws Exception {
        when(transferService.execute(any()))
                .thenThrow(new InsufficientFundsException(SOURCE, BigDecimal.ONE, BigDecimal.TEN));

        mvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("InsufficientFunds"));
    }

    @Test
    void fraudOutageIsServiceUnavailable() throws Exception {
        when(transferService.execute(any()))
                .thenThrow(new FraudServiceUnavailableException(new RuntimeException("timeout")));

        mvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("FraudServiceUnavailable"));
    }
}
