package br.com.pocbank.account.web;

import br.com.pocbank.account.application.port.FraudServiceUnavailableException;
import br.com.pocbank.account.domain.DomainException;
import br.com.pocbank.account.domain.account.AccountBlockedException;
import br.com.pocbank.account.domain.account.AccountNotFoundException;
import br.com.pocbank.account.domain.account.DuplicateAccountException;
import br.com.pocbank.account.domain.account.InsufficientFundsException;
import br.com.pocbank.account.domain.transfer.InvalidTransferException;
import br.com.pocbank.account.domain.transfer.TransferNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Map;

/** Traduz exceções de domínio em respostas RFC 9457 (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<Class<? extends DomainException>, HttpStatus> STATUS_BY_EXCEPTION = Map.of(
            AccountNotFoundException.class, HttpStatus.NOT_FOUND,
            TransferNotFoundException.class, HttpStatus.NOT_FOUND,
            DuplicateAccountException.class, HttpStatus.CONFLICT,
            InsufficientFundsException.class, HttpStatus.UNPROCESSABLE_ENTITY,
            AccountBlockedException.class, HttpStatus.UNPROCESSABLE_ENTITY,
            InvalidTransferException.class, HttpStatus.BAD_REQUEST);

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        HttpStatus status = STATUS_BY_EXCEPTION.getOrDefault(ex.getClass(), HttpStatus.UNPROCESSABLE_ENTITY);
        return problem(status, ex.getClass().getSimpleName().replace("Exception", ""), ex.getMessage());
    }

    @ExceptionHandler(FraudServiceUnavailableException.class)
    ProblemDetail handleFraudUnavailable(FraudServiceUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "FraudServiceUnavailable",
                "Não foi possível validar a transação agora. Tente novamente em instantes.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrency(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "ConcurrentModification",
                "O recurso foi alterado por outra operação. Tente novamente.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://api.pocbank.dev/problems/" + code));
        problem.setTitle(code);
        return problem;
    }
}
