package br.com.pocbank.lambda;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record StatementReport(String sourceKey, int entries, BigDecimal totalCredits, BigDecimal totalDebits,
                              BigDecimal totalFees, BigDecimal net) {

    private static final String HEADER_DIRECTION = "direction";
    private static final String HEADER_AMOUNT = "amount";
    private static final String HEADER_FEE = "fee";

    /** Lê o CSV gerado pelo statement-service localizando colunas pelo cabeçalho (robusto a reordenação). */
    public static StatementReport fromCsv(String sourceKey, String csv) {
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("CSV vazio: " + sourceKey);
        }
        List<String> header = List.of(lines.get(0).split(","));
        int direction = indexOf(header, HEADER_DIRECTION);
        int amount = indexOf(header, HEADER_AMOUNT);
        int fee = indexOf(header, HEADER_FEE);

        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split(",");
            BigDecimal value = new BigDecimal(columns[amount]);
            if ("CREDIT".equals(columns[direction])) {
                credits = credits.add(value);
            } else {
                debits = debits.add(value);
                fees = fees.add(new BigDecimal(columns[fee]));
            }
        }
        return new StatementReport(sourceKey, lines.size() - 1, scale(credits), scale(debits), scale(fees),
                scale(credits.subtract(debits)));
    }

    private static int indexOf(List<String> header, String column) {
        int index = header.indexOf(column);
        if (index < 0) {
            throw new IllegalArgumentException("Coluna ausente no CSV: " + column);
        }
        return index;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
