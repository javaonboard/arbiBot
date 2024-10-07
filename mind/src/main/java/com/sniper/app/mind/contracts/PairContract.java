package com.sniper.app.mind.contracts;


import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.Contract;
import org.web3j.crypto.Credentials;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.tuples.generated.Tuple3;
import org.web3j.protocol.core.RemoteCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class PairContract extends Contract {
    private static final Logger logger = LoggerFactory.getLogger(PairContract.class);

    // Load the Pair contract from the given address, using Web3j and credentials
    public static PairContract load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        logger.info("Loading PairContract at address: {}", contractAddress);
        return new PairContract(contractAddress, web3j, credentials, gasProvider);
    }

    protected PairContract(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        super("", contractAddress, web3j, credentials, gasProvider);
        logger.info("Initialized PairContract for address: {}", contractAddress);
    }

    // Retrieve the first token in the pair (token0)
    public RemoteCall<String> token0() {
        final Function function = new Function(
                "token0",
                Collections.emptyList(),
                Arrays.asList(new TypeReference<Address>() {})
        );
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    // Retrieve the second token in the pair (token1)
    public RemoteCall<String> token1() {
        final Function function = new Function(
                "token1",
                Collections.emptyList(),
                Arrays.asList(new TypeReference<Address>() {})
        );
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    // Retrieve reserves and timestamp from the pair contract
    private List<Type> fetchReserves(String uuid) throws Exception {
        try {
            final Function function = new Function(
                    "getReserves",
                    Collections.emptyList(),
                    Arrays.asList(
                            new TypeReference<Uint256>() {},
                            new TypeReference<Uint256>() {},
                            new TypeReference<Uint32>() {}
                    )
            );

            logger.info("Fetching reserves for contract: {}. [UUID: {}]", getContractAddress(), uuid);
            List<Type> result = executeRemoteCallMultipleValueReturn(function).send();
            logger.info("Fetched raw reserves for contract: {}. [UUID: {}]", getContractAddress(), uuid);

            return result;
        } catch (Exception e) {
            logger.error("Error fetching reserves from pair contract: {}. [UUID: {}]", getContractAddress(), uuid, e);
            throw e;
        }
    }

    public BigDecimal getReserve0(int token0Decimals, String uuid) throws Exception {
        List<Type> result = fetchReserves(uuid);
        BigInteger reserve0 = (BigInteger) result.get(0).getValue();
        logger.info("Raw reserve0 for pair {}: {}. [UUID: {}]", getContractAddress(), reserve0, uuid);
        BigDecimal convertedReserve0 = new BigDecimal(reserve0).divide(BigDecimal.TEN.pow(token0Decimals));
        logger.info("Converted reserve0 for pair {}: {}. [UUID: {}]", getContractAddress(), convertedReserve0, uuid);
        return convertedReserve0;
    }

    public BigDecimal getReserve1(int token1Decimals, String uuid) throws Exception {
        List<Type> result = fetchReserves(uuid);
        BigInteger reserve1 = (BigInteger) result.get(1).getValue();
        logger.info("Raw reserve1 for pair {}: {}. [UUID: {}]", getContractAddress(), reserve1, uuid);
        BigDecimal convertedReserve1 = new BigDecimal(reserve1).divide(BigDecimal.TEN.pow(token1Decimals));
        logger.info("Converted reserve1 for pair {}: {}. [UUID: {}]", getContractAddress(), convertedReserve1, uuid);
        return convertedReserve1;
    }

    public RemoteCall<Tuple3<Uint256, Uint256, Uint32>> getReserves() {
        final Function function = new Function(
                "getReserves",
                Collections.emptyList(), // No inputs required
                Arrays.asList(
                        new TypeReference<Uint256>() {}, // Reserve0
                        new TypeReference<Uint256>() {}, // Reserve1
                        new TypeReference<Uint32>() {}   // Block timestamp last
                )
        );

        return new RemoteCall<>(() -> {
            List<Type> result = executeCallMultipleValueReturn(function);
            Uint256 reserve0 = (Uint256) result.get(0);
            Uint256 reserve1 = (Uint256) result.get(1);
            Uint32 blockTimestampLast = (Uint32) result.get(2);
            return new Tuple3<>(reserve0, reserve1, blockTimestampLast);
        });
    }

    public Tuple3<BigDecimal, BigDecimal, Uint32> getReserves(int token0Decimals, int token1Decimals, String uuid) throws Exception {
        List<Type> result = fetchReserves(uuid);
        BigInteger reserve0Raw = (BigInteger) result.get(0).getValue();
        BigInteger reserve1Raw = (BigInteger) result.get(1).getValue();
        Uint32 lastBlockTimestamp = (Uint32) result.get(2);

        logger.info("Raw reserves for pair {}: reserve0={}, reserve1={}. [UUID: {}]", getContractAddress(), reserve0Raw, reserve1Raw, uuid);

        BigDecimal reserve0 = new BigDecimal(reserve0Raw).divide(BigDecimal.TEN.pow(token0Decimals));
        BigDecimal reserve1 = new BigDecimal(reserve1Raw).divide(BigDecimal.TEN.pow(token1Decimals));

        logger.info("Converted reserves for pair {}: reserve0={}, reserve1={}. [UUID: {}]", getContractAddress(), reserve0, reserve1, uuid);

        return new Tuple3<>(reserve0, reserve1, lastBlockTimestamp);
    }

    // Retrieve the timestamp when the reserves were last updated
    public RemoteCall<Uint32> getLastBlockTimestamp(String uuid) {
        return new RemoteCall<>(() -> {
            Uint32 timestamp = getReserves(0, 0, uuid).getValue3(); // token decimals not relevant for timestamp
            logger.info("Fetched last block timestamp for pair {}: {}. [UUID: {}]", getContractAddress(), timestamp, uuid);
            return timestamp;
        });
    }
}
