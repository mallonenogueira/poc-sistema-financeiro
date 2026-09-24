package br.com.pocbank.account.domain;

/**
 * Raiz das exceções de negócio. A camada web traduz cada subtipo para um
 * ProblemDetail (RFC 9457) sem que o domínio conheça HTTP.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
