package br.com.pocbank.statement.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/** Endpoints funcionais do WebFlux (alternativa aos controllers anotados). */
@Configuration
public class StatementRouter {

    @Bean
    public RouterFunction<ServerResponse> statementRoutes(StatementHandler handler) {
        return route(GET("/api/statements/{accountId}/stream"), handler::stream)
                .andRoute(GET("/api/statements/{accountId}"), handler::list)
                .andRoute(POST("/api/statements/{accountId}/exports"), handler::export);
    }
}
