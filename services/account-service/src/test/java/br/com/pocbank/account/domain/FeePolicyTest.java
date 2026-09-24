package br.com.pocbank.account.domain;

import br.com.pocbank.account.domain.fee.FeePolicyResolver;
import br.com.pocbank.account.domain.fee.PixFeePolicy;
import br.com.pocbank.account.domain.fee.TedFeePolicy;
import br.com.pocbank.account.domain.transfer.TransferType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeePolicyTest {

    private final FeePolicyResolver resolver = new FeePolicyResolver(List.of(new PixFeePolicy(), new TedFeePolicy()));

    @ParameterizedTest(name = "{0} de {1} custa {2}")
    @CsvSource({
            "PIX, 10.00,     0.00",
            "PIX, 99999.99,  0.00",
            "TED, 100.00,    5.10",
            "TED, 1000.00,   6.00",
            "TED, 20000.00,  25.00",
            "TED, 1000000,   25.00"
    })
    void calculatesFeeByTransferType(TransferType type, BigDecimal amount, BigDecimal expectedFee) {
        assertThat(resolver.resolve(type).calculate(amount)).isEqualByComparingTo(expectedFee);
    }

    @Test
    void failsFastWhenAPolicyIsMissing() {
        assertThatThrownBy(() -> new FeePolicyResolver(List.of(new PixFeePolicy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TED");
    }
}
