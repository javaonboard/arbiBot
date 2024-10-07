package com.sniper.app.mind.model;

import java.math.BigDecimal;

public class PriceDexInfo {
    private final BigDecimal price;
    private final String dexAddress;

    public PriceDexInfo(BigDecimal price, String dexAddress) {
        this.price = price;
        this.dexAddress = dexAddress;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getDexAddress() {
        return dexAddress;
    }
}
