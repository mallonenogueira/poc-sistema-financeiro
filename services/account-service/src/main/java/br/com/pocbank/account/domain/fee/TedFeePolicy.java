package br.com.pocbank.account.domain.fee;

import br.com.pocbank.account.domain.Amounts;
import br.com.pocbank.account.domain.transfer.TransferType;

import java.math.BigDecimal;

/** TED: tarifa fixa + 0,1% do valor, limitada a um teto. */
public class TedFeePolicy implements FeePolicy {

    private static final BigDecimal FIXED = new BigDecimal("5.00");
    private static final BigDecimal RATE = new BigDecimal("0.001");
    private static final BigDecimal CAP = new BigDecimal("25.00");

    @Override
    public TransferType type() {
        return TransferType.TED;
    }

    @Override
    public BigDecimal calculate(BigDecimal amount) {
        BigDecimal fee = FIXED.add(amount.multiply(RATE));
        return Amounts.normalize(fee.min(CAP));
    }
}
