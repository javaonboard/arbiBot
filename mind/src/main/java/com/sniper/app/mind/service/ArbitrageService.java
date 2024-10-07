package com.sniper.app.mind.service;

import com.sniper.app.mind.config.Web3Config;
import com.sniper.app.mind.contracts.FactoryContract;
import com.sniper.app.mind.contracts.PairContract;
import com.sniper.app.mind.contracts.RouterContract;
import com.sniper.app.mind.model.PriceDexInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ArbitrageService {
    private static final Logger logger = LoggerFactory.getLogger(ArbitrageService.class);

    @Autowired
    private Web3Config multiProviderService;

    @Autowired
    private CacheService cacheService;

    @Value("${my.private.key}")
    private String privateKey;

    @Value("${trade.allocation.percentage}")
    private BigDecimal tradeAllocationPercentage;

    @Value("${trade.allocation.availableFunds}")
    private BigDecimal availableFunds;

    @Value("${trade.allocation.minProfit}")
    private BigDecimal minProfitThreshold;

    @Value("#{'${dex.routers}'.split(',')}")
    private List<String> dexRouters;

    @Value("${token.fetch.limit}")
    private int tokenFetchLimit;

    @Value("${main.uniSwap.factory.address}")
    private String uniSwapFactoryAddress;

    @Value("${request.sleep.time.ms}")
    private int requestSleepTime;
    private Credentials credentials;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Set<String> nonExistentPairsCache = new HashSet<>();
    private final Set<String> highProfitPairsCache = new HashSet<>();
    private final Set<String> skippedPairsCache = new HashSet<>();

    @PostConstruct
    public void init() {
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("Private key is not configured");
        }
        this.credentials = Credentials.create(privateKey.trim());
        logger.info("Credentials initialized with private key.");
    }

    public void monitorArbitrageOnExchanges() throws Exception {
        findAndMonitorArbitrageOpportunities(uniSwapFactoryAddress);
    }

    public void findAndMonitorArbitrageOpportunities(String factoryAddress) throws Exception {
        List<TokenPair> allPairs = getCachedOrFetchTokenPairs(factoryAddress);
        Set<TokenTriple> allTriples = generateTokenTriples(allPairs);
        logger.info("Successfully fetched all pairs and triples: Pairs: {}, Triples: {}.", allPairs.size(), allTriples.size());

        int batchSize = 10;
        for (int i = 0; i < allTriples.size(); i += batchSize) {
            List<TokenTriple> batch = new ArrayList<>(allTriples).subList(i, Math.min(i + batchSize, allTriples.size()));
            processBatch(batch);
        }
    }

    public Set<TokenTriple> generateTokenTriples(List<TokenPair> pairs) {
        logger.info("Generating token triples from {} pairs.", pairs.size());

        List<String> tokens = new ArrayList<>();
        Map<String, BigDecimal> tokenBalances = new HashMap<>();

        // Collect unique tokens and their balances
        for (TokenPair pair : pairs) {
            tokens.add(pair.getTokenA());
            tokens.add(pair.getTokenB());
        }

        // Get wallet balances for each token
        for (String token : tokens) {
            try {
                BigDecimal balance = fetchWalletBalance(token, UUID.randomUUID().toString());
                if (balance.compareTo(BigDecimal.ZERO) > 0) {
                    tokenBalances.put(token, balance);
                }
            } catch (Exception e) {
                logger.error("Error checking wallet balance for token: {}. Skipping it.", token, e);
            }
        }

        // Sort tokens by balance in descending order
        tokens = new ArrayList<>(tokenBalances.keySet());
        tokens.sort((a, b) -> tokenBalances.get(b).compareTo(tokenBalances.get(a)));

        logger.info("Tokens sorted by balance: {}.", tokens);

        // Generate triples prioritizing higher balance tokens
        Set<TokenTriple> triples = new HashSet<>();
        for (String tokenA : tokens) {
            for (String tokenB : tokens) {
                if (!tokenA.equals(tokenB)) {
                    for (String tokenC : tokens) {
                        if (!tokenC.equals(tokenA) && !tokenC.equals(tokenB)) {
                            TokenTriple triple = new TokenTriple(tokenA, tokenB, tokenC);
                            triples.add(triple);
                            logger.info("Generated triple: {} -> {} -> {}. ", tokenA, tokenB, tokenC);
                        }
                    }
                }
            }
        }

        logger.info("Total generated token triples: {}.", triples.size());
        return triples;
    }

    public List<TokenPair> getCachedOrFetchTokenPairs(String factoryAddress) throws Exception {
        List<TokenPair> cachedPairs = cacheService.getCachedValue("cachedTokenPairs", List.class);
        if (cachedPairs != null && !cachedPairs.isEmpty()) {
            logger.info("Using cached pairs.");
            return cachedPairs;
        } else {
            logger.info("No cached pairs found, fetching new pairs.");
            return fetchLimitedPairs(factoryAddress);
        }
    }

    public List<TokenPair> fetchLimitedPairs(String factoryAddress) throws Exception {
        Web3j web3j = multiProviderService.getNextProvider();
        FactoryContract factoryContract = FactoryContract.load(factoryAddress, web3j, credentials, new DefaultGasProvider());

        List<TokenPair> pairs = new ArrayList<>();
        BigInteger totalPairs = factoryContract.allPairsLength().send();
        BigInteger fetchLimit = BigInteger.valueOf(Math.min(tokenFetchLimit, totalPairs.intValue()));

        for (BigInteger i = BigInteger.ZERO; i.compareTo(fetchLimit) < 0; i = i.add(BigInteger.ONE)) {
            String pairAddress = factoryContract.allPairs(i).send();
            PairContract pairContract = PairContract.load(pairAddress, web3j, credentials, new DefaultGasProvider());

            String tokenA = pairContract.token0().send();
            String tokenB = pairContract.token1().send();
            pairs.add(new TokenPair(tokenA, tokenB));
        }

        return pairs;
    }

    public BigDecimal fetchWalletBalance(String tokenAddress, String uuid) throws Exception {
        logger.info("Fetching wallet balance for token: {}. [UUID: {}]", tokenAddress, uuid);

       /* // Construct the cache key with the token address and wallet address for uniqueness
        String cacheKey = "balance-" + tokenAddress;
        BigDecimal cachedBalance = cacheService.getCachedValue(cacheKey, BigDecimal.class);
        if (cachedBalance != null) {
            logger.info("Using cached balance for token: {}. [UUID: {}]", tokenAddress, uuid);
            return cachedBalance;
        }

        try {
            // Load the ERC20 contract
            ERC20Contract tokenContract = ERC20Contract.load(tokenAddress, multiProviderService.getNextProvider(), credentials, new DefaultGasProvider());

            // Fetch the balance as Uint256
            Uint256 balance = tokenContract.balanceOf(credentials.getAddress()).send();

            // Convert Uint256 to BigInteger
            BigInteger balanceInWei = balance.getValue();

            // Convert to human-readable format using token decimals
            int decimals = getTokenDecimals(tokenAddress, uuid);
            BigDecimal balanceInDecimal = new BigDecimal(balanceInWei).divide(BigDecimal.TEN.pow(decimals));

            // Cache the balance for future use
            cacheService.cacheValueWithExpiry(cacheKey, balanceInDecimal, 60); // Cache for 60 seconds
            logger.info("Fetched and cached balance for token: {} = {}. [UUID: {}]", tokenAddress, balanceInDecimal, uuid);

            return balanceInDecimal;
        } catch (Exception e) {
            logger.error("Error fetching wallet balance for token: {}. [UUID: {}]", tokenAddress, uuid, e);
            throw e;
        }*/
        return new BigDecimal(0.1);
    }


    public void processBatch(List<TokenTriple> batch) {
        logger.info("Processing a batch of {} token triples.", batch.size());

        for (TokenTriple triple : batch) {
            String uuid = UUID.randomUUID().toString();
            String tripleKey = triple.getTokenA() + "-" + triple.getTokenB() + "-" + triple.getTokenC();
            logger.info("Processing triple: {} with UUID: {}", tripleKey, uuid);

            if (skippedPairsCache.contains(tripleKey)) {
                logger.info("Skipping triple {} as it's in the skippedPairsCache. [UUID: {}]", tripleKey, uuid);
                continue;
            }
            if (highProfitPairsCache.contains(tripleKey)) {
                BigDecimal amountToUse = availableFunds.multiply(tradeAllocationPercentage);
                logger.info("Triple {} found in highProfitPairsCache. Attempting to execute arbitrage with amount: {}. [UUID: {}]", tripleKey, amountToUse, uuid);
                executeArbitrage(triple, amountToUse, uuid);
            }
        }

        for (TokenTriple triple : batch) {
            String uuid = UUID.randomUUID().toString();
            executorService.submit(() -> {
                try {
                    String tripleKey = triple.getTokenA() + "-" + triple.getTokenB() + "-" + triple.getTokenC();
                    logger.info("Checking arbitrage for triple: {}. [UUID: {}]", tripleKey, uuid);

                    if (skippedPairsCache.contains(tripleKey)) {
                        logger.info("Skipping triple {} as it's already in the skippedPairsCache. [UUID: {}]", tripleKey, uuid);
                        return;
                    }
                    if (highProfitPairsCache.contains(tripleKey)) {
                        logger.info("Triple {} already in highProfitPairsCache. Skipping further checks. [UUID: {}]", tripleKey, uuid);
                        return;
                    }

                    // Directly proceed to check arbitrage using DEX getAmountsOut
                    checkAndExecuteTriangularArbitrage(triple, uuid);

                } catch (Exception e) {
                    logger.error("Error processing triple: {}. [UUID: {}]", triple, uuid, e);
                }
            });
        }
    }

    private void checkAndExecuteTriangularArbitrage(TokenTriple triple, String uuid) {
        try {
            logger.info("Checking triangular arbitrage for triple: {} -> {} -> {}. [UUID: {}]",
                    triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), uuid);

            // Fetch the triangular price
            PriceDexInfo priceABCA = fetchTriangularPrice(triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), availableFunds.multiply(tradeAllocationPercentage), uuid);
            if(priceABCA.getPrice().compareTo(BigDecimal.ZERO)<=0){
                logger.info("Router Final output is 0 or less for Triangular tokens {} {}",triple.toString(),priceABCA.getPrice());
            }else {
                logger.info("Final output for triangular path {} -> {} -> {} -> {}: {}. [UUID: {}]",
                        triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), triple.getTokenA(), priceABCA.getPrice(), uuid);

                // Create the swap function object (example, adjust as per your swap function)
                Function swapFunction = createSwapFunction(triple, availableFunds.multiply(tradeAllocationPercentage));

                // Estimate the gas limit for the swap
                BigInteger gasLimit = estimateGasLimit(credentials.getAddress(), priceABCA.getDexAddress(), swapFunction, multiProviderService.getNextProvider());

                // Calculate the net profit before fees and slippage
                BigDecimal gasCost = estimateGasCost(gasLimit);
                BigDecimal profit = priceABCA.getPrice().subtract(availableFunds.multiply(tradeAllocationPercentage));
                BigDecimal netProfit = profit.subtract(gasCost);
                logger.info("Net profit after subtracting gas fees: {}. [UUID: {}]", netProfit, uuid);

                // Adjust for slippage
                BigDecimal slippageTolerance = BigDecimal.valueOf(0.01); // 1% slippage tolerance for example
                BigDecimal minAcceptableOutput = availableFunds.multiply(tradeAllocationPercentage).multiply(BigDecimal.ONE.subtract(slippageTolerance));
                logger.info("Minimum acceptable output after slippage tolerance: {}. [UUID: {}]", minAcceptableOutput, uuid);

                // Final check: execute only if net profit is greater than zero and final output is greater than minimum acceptable output
                if (netProfit.compareTo(BigDecimal.ZERO) > 0 && priceABCA.getPrice().compareTo(minAcceptableOutput) > 0) {
                    logger.info("Arbitrage opportunity found with net profit: {}. [UUID: {}]", netProfit, uuid);
                    executeArbitrage(triple, availableFunds.multiply(tradeAllocationPercentage), uuid);
                } else {
                    logger.info("No profitable arbitrage opportunity after fees and slippage. [UUID: {}]", uuid);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking arbitrage for triple: {} -> {} -> {}. [UUID: {}]",
                    triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), uuid, e);
        }

    }
    private Function createSwapFunction(TokenTriple triple, BigDecimal amountIn) {
        // Create the parameters for the swap function based on the contract's ABI
        // Adjust as per the actual Uniswap Router contract function you are using
        List<Address> path = Arrays.asList(new Address(triple.getTokenA()), new Address(triple.getTokenB()), new Address(triple.getTokenC()), new Address(triple.getTokenA()));
        Uint256 amountInUint256 = new Uint256(Convert.toWei(amountIn, Convert.Unit.ETHER).toBigInteger());

        return new Function(
                "swapExactTokensForTokens",
                Arrays.asList(amountInUint256, new Uint256(BigInteger.ZERO), new DynamicArray<>(Address.class, path), new Address(credentials.getAddress()), new Uint256(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 1200))),
                Arrays.asList(new TypeReference<Uint256>() {})
        );
    }

    private BigDecimal estimateGasCost(BigInteger gasLimit) {
        try {
            BigInteger gasPrice = multiProviderService.getNextProvider().ethGasPrice().send().getGasPrice();
            BigInteger gasCostInWei = gasPrice.multiply(gasLimit);
            BigDecimal gasCostInEther = new BigDecimal(gasCostInWei).divide(BigDecimal.TEN.pow(18));
            logger.info("Estimated gas cost in Ether: {}", gasCostInEther);
            return gasCostInEther;
        } catch (Exception e) {
            logger.error("Error estimating gas cost: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }




    public void executeArbitrage(TokenTriple triple, BigDecimal amountToUse, String uuid) {
        logger.info("**** Executing Arbitrage for triple: {} -> {} -> {} with amount: {}. [UUID: {}]",
                triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), amountToUse, uuid);

        try {
            // Placeholder: Call the swap function on the RouterContract here
            boolean swapSuccess = true; // Assuming the swap was successful for demonstration purposes

            if (swapSuccess) {
                logger.info("Arbitrage executed successfully for triple: {} -> {} -> {}. [UUID: {}]",
                        triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), uuid);
            } else {
                logger.warn("Arbitrage execution failed for triple: {} -> {} -> {}. [UUID: {}]",
                        triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), uuid);
            }
        } catch (Exception e) {
            logger.error("Error executing arbitrage for triple: {} -> {} -> {}. [UUID: {}]",
                    triple.getTokenA(), triple.getTokenB(), triple.getTokenC(), uuid, e);
        }
    }
    private BigInteger estimateGasLimit(String fromAddress, String contractAddress, Function function, Web3j web3j) {
        try {
            // Encode the function call
            String encodedFunction = FunctionEncoder.encode(function);

            // Create a transaction object for estimation
            Transaction transaction = Transaction.createEthCallTransaction(
                    fromAddress,
                    contractAddress,
                    encodedFunction
            );

            // Estimate the gas
            BigInteger gasLimit = web3j.ethEstimateGas(transaction).send().getAmountUsed();
            logger.info("Estimated gas limit: {}", gasLimit);

            // Add a buffer to ensure the transaction goes through
            return gasLimit.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100)); // 20% buffer
        } catch (Exception e) {
            logger.error("Error estimating gas limit: {}", e.getMessage());
            // Return a default gas limit in case of an error
            return BigInteger.valueOf(300000); // Adjust this default value as necessary
        }
    }







    public PriceDexInfo fetchTriangularPrice(String tokenA, String tokenB, String tokenC, BigDecimal inputAmount, String uuid) throws Exception {
        logger.info("Fetching price for triangular path {} -> {} -> {} -> {}. [UUID: {}]", tokenA, tokenB, tokenC, tokenA, uuid);
        BigDecimal bestPrice = BigDecimal.ZERO;
        String bestDex = "";

        for (String dexRouter : dexRouters) {
            try {
                Web3j web3j = multiProviderService.getNextProvider();
                ReadonlyTransactionManager transactionManager = new ReadonlyTransactionManager(web3j, dexRouter);
                RouterContract routerContract = RouterContract.load(dexRouter, web3j, transactionManager, new DefaultGasProvider());

                Uint256 amountIn = new Uint256(Convert.toWei(inputAmount, Convert.Unit.ETHER).toBigInteger());
                List<Address> path = Arrays.asList(new Address(tokenA), new Address(tokenB), new Address(tokenC), new Address(tokenA));
                List<Uint256> amountsOut = routerContract.getAmountsOut(amountIn, path,uuid).send();

                BigInteger finalAmountOut = amountsOut.get(amountsOut.size() - 1).getValue();
                int tokenADecimals = getTokenDecimals(tokenA, web3j);
                BigDecimal tokenPrice = new BigDecimal(finalAmountOut).divide(BigDecimal.TEN.pow(tokenADecimals));

                logger.info("Calculated token price of WEI {} > OUTPUT < {} for path {} -> {} -> {} -> {}: final amount after decimal {} final amount in WEI {}. [UUID: {}]",amountIn.getValue(),finalAmountOut, tokenA, tokenB, tokenC, tokenA, tokenPrice,finalAmountOut, uuid);

                if (amountIn.getValue().compareTo(amountsOut.get(amountsOut.size() - 1).getValue()) < 0) {
                    bestPrice = tokenPrice;
                    bestDex = dexRouter;
                }
            } catch (Exception e) {
                logger.warn("Error fetching price from DEX {} for path {} -> {} -> {}. [UUID: {}]. Error: {}", dexRouter, tokenA, tokenB, tokenC, uuid, e.getMessage());
            }
        }

        return new PriceDexInfo(bestPrice, bestDex);
    }

    public int getTokenDecimals(String tokenAddress, Web3j web3j) {
        try {
            Function function = new Function("decimals", Collections.emptyList(), Collections.singletonList(new TypeReference<Uint8>() {}));
            String encodedFunction = FunctionEncoder.encode(function);
            Transaction transaction = Transaction.createEthCallTransaction(tokenAddress, tokenAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            if (response.hasError()) {
                logger.error("Error fetching decimals for token at address {}: {}", tokenAddress, response.getError().getMessage());
                return -1;
            }

            String rawResponse = response.getResult();
            if (rawResponse == null || rawResponse.equals("0x")) {
                logger.error("Invalid response fetching decimals for token at address {}: Empty or null result", tokenAddress);
                return -1;
            }

            BigInteger decimalsValue = new BigInteger(rawResponse.substring(2), 16);
            logger.info("Decimals fetched for token at address {}: {}", tokenAddress, decimalsValue.intValue());

            return decimalsValue.intValue();
        } catch (Exception e) {
            logger.error("Error fetching decimals for token at address {}: {}", tokenAddress, e.getMessage());
            return -1;
        }
    }


    // Nested classes for TokenTriple and PriceDexInfo
    public static class TokenTriple {
        private final String tokenA;
        private final String tokenB;
        private final String tokenC;

        public TokenTriple(String tokenA, String tokenB, String tokenC) {
            this.tokenA = tokenA;
            this.tokenB = tokenB;
            this.tokenC = tokenC;
        }

        public String getTokenA() {
            return tokenA;
        }

        public String getTokenB() {
            return tokenB;
        }

        public String getTokenC() {
            return tokenC;
        }

        @Override
        public String toString() {
            return tokenA + " -> " + tokenB + " -> " + tokenC;
        }
    }

    public static class PriceDexInfo {
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
    public static class TokenPair {
        private final String tokenA;
        private final String tokenB;

        public TokenPair(String tokenA, String tokenB) {
            this.tokenA = tokenA;
            this.tokenB = tokenB;
        }

        public String getTokenA() { return tokenA; }
        public String getTokenB() { return tokenB; }
    }


}