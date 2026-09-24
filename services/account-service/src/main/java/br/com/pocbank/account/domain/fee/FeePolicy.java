package br.com.pocbank.account.domain.fee;

import br.com.pocbank.account.domain.transfer.TransferType;

import java.math.BigDecimal;

/**
 * Strategy: cada modalidade de transferência tem sua regra de tarifa.
 * Nova modalidade = nova implementação, sem alterar código existente (OCP).
 */
public interface FeePolicy {

    TransferType type();

    BigDecimal calculate(BigDecimal amount);
}
