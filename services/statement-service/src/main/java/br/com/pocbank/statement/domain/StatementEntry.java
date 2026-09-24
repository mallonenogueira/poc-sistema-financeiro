package br.com.pocbank.statement.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model (CQRS) do extrato, desnormalizado por conta. O {@code id} é
 * determinístico ({@code transferId:DIREÇÃO}), o que torna a projeção idempotente:
 * reprocessar o mesmo evento Kafka apenas sobrescreve o documento.
 */
@Document("statement_entries")
@CompoundIndex(name = "account_timeline", def = "{'accountId': 1, 'occurredAt': -1}")
public record StatementEntry(
        @Id String id,
        String accountId,
        String transferId,
        EntryDirection direction,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal fee,
        String counterpartyAccountId,
        String type,
        Instant occurredAt) {

    public static String idFor(String transferId, EntryDirection direction) {
        return transferId + ":" + direction;
    }

    public BigDecimal signedAmount() {
        return direction == EntryDirection.DEBIT ? amount.negate() : amount;
    }
}
