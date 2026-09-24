package br.com.pocbank.account.domain.fee;

import br.com.pocbank.account.domain.transfer.TransferType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Seleciona a {@link FeePolicy} pela modalidade. Falha no boot se faltar alguma. */
public class FeePolicyResolver {

    private final Map<TransferType, FeePolicy> policies = new EnumMap<>(TransferType.class);

    public FeePolicyResolver(List<FeePolicy> policies) {
        policies.forEach(policy -> this.policies.put(policy.type(), policy));
        for (TransferType type : TransferType.values()) {
            if (!this.policies.containsKey(type)) {
                throw new IllegalStateException("Nenhuma FeePolicy registrada para " + type);
            }
        }
    }

    public FeePolicy resolve(TransferType type) {
        return policies.get(type);
    }
}
