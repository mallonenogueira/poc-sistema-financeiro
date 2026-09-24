package br.com.pocbank.account.domain.fee;

import br.com.pocbank.account.domain.Amounts;
import br.com.pocbank.account.domain.transfer.TransferType;

import java.math.BigDecimal;

/** PIX para pessoa física é isento. */
public class PixFeePolicy implements FeePolicy {

    @Override
    public TransferType type() {
        return TransferType.PIX;
    }

    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return Amounts.normalize(BigDecimal.ZERO);
    }
}
