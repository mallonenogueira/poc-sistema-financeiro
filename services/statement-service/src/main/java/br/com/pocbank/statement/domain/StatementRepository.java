package br.com.pocbank.statement.domain;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface StatementRepository extends ReactiveMongoRepository<StatementEntry, String> {

    Flux<StatementEntry> findByAccountIdOrderByOccurredAtDesc(String accountId);
}
