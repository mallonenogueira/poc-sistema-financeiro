package br.com.pocbank.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Regras de arredondamento monetário centralizadas (BRL, 2 casas, HALF_EVEN). */
public final class Amounts {

    public static final int SCALE = 2;

    private Amounts() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
