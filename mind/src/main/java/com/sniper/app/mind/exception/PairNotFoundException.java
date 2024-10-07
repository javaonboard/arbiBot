package com.sniper.app.mind.exception;

public class PairNotFoundException extends  Exception {
    public PairNotFoundException(String tokenA, String tokenB) {
        super("Pair not found for tokens: " + tokenA + " and " + tokenB);
    }

}
