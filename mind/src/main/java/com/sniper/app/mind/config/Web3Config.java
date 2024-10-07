package com.sniper.app.mind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Web3Config {

    private final List<String> providers = new ArrayList<>();
    private final AtomicInteger currentProviderIndex = new AtomicInteger(0);

    @Bean
    public Web3j web3j() {
        // Add multiple providers (Infura, Alchemy, QuickNode, etc.)
        //providers.add("https://sepolia.infura.io/v3/0e82e054d83f41cfae8cb9da44689296");
        //providers.add("https://eth-sepolia.g.alchemy.com/v2/1bGUmmUlhoY_m5kABDVl5KScFrBuKCZP");
        providers.add("https://mainnet.infura.io/v3/0e82e054d83f41cfae8cb9da44689296");
        providers.add("https://eth-mainnet.g.alchemy.com/v2/1bGUmmUlhoY_m5kABDVl5KScFrBuKCZP");

        //providers.add("https://your-node.quicknode.pro/quicknode-key");
        // Start with the first provider
        return getNextProvider();
    }

    public Web3j getNextProvider() {
        // Rotate through the list of providers using round-robin
        int index = currentProviderIndex.getAndIncrement() % providers.size();
        String currentProviderUrl = providers.get(index);
        return Web3j.build(new HttpService(currentProviderUrl));
    }
}
