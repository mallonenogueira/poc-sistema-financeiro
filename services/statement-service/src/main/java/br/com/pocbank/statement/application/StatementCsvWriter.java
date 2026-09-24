package br.com.pocbank.statement.application;

import br.com.pocbank.statement.domain.StatementEntry;

import java.util.List;
import java.util.stream.Collectors;

public final class StatementCsvWriter {

    static final String HEADER = "occurredAt,transferId,direction,type,amount,fee,counterpartyAccountId";

    private StatementCsvWriter() {
    }

    public static String write(List<StatementEntry> entries) {
        return entries.stream()
                .map(e -> String.join(",", e.occurredAt().toString(), e.transferId(), e.direction().name(),
                        e.type(), e.amount().toPlainString(), e.fee().toPlainString(), e.counterpartyAccountId()))
                .collect(Collectors.joining("\n", HEADER + "\n", "\n"));
    }
}
