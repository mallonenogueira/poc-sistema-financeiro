package br.com.pocbank.account.web;

import br.com.pocbank.account.application.AccountService;
import br.com.pocbank.account.domain.account.Account;
import br.com.pocbank.account.web.dto.AccountDtos.AccountResponse;
import br.com.pocbank.account.web.dto.AccountDtos.OpenAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Contas")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Abre uma conta")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.open(request.holderName(), request.document(), request.initialDeposit());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping
    @Operation(summary = "Lista contas")
    public List<AccountResponse> list() {
        return accountService.list().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta uma conta")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.find(id));
    }
}
