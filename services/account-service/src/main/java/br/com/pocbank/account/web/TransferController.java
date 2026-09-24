package br.com.pocbank.account.web;

import br.com.pocbank.account.application.TransferCommand;
import br.com.pocbank.account.application.TransferService;
import br.com.pocbank.account.domain.transfer.Transfer;
import br.com.pocbank.account.web.dto.TransferDtos.TransferRequest;
import br.com.pocbank.account.web.dto.TransferDtos.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transferências")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Retorna 201 também para transferências rejeitadas pelo antifraude: o recurso
     * foi criado e o resultado de negócio está em {@code status}.
     */
    @PostMapping
    @Operation(summary = "Executa uma transferência (idempotente por Idempotency-Key)")
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @Valid @RequestBody TransferRequest request) {
        Transfer transfer = transferService.execute(new TransferCommand(request.sourceAccountId(),
                request.targetAccountId(), request.amount(), request.type(), idempotencyKey));
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(transfer.getId()).toUri();
        return ResponseEntity.created(location).body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta uma transferência")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferService.findById(id));
    }
}
