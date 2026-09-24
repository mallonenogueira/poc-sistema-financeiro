package br.com.pocbank.account.application.port;

/**
 * Antifraude indisponível. Política fail-closed: sem análise, sem transferência.
 */
public class FraudServiceUnavailableException extends RuntimeException {

    public FraudServiceUnavailableException(Throwable cause) {
        super("Serviço antifraude indisponível", cause);
    }
}
