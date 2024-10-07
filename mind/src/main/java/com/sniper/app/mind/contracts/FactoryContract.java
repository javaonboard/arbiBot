package com.sniper.app.mind.contracts;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.tx.Contract;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

public class FactoryContract extends Contract {

    // Load the Factory contract from a given address
    public static FactoryContract load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        return new FactoryContract(contractAddress, web3j, credentials, gasProvider);
    }

    // Constructor for FactoryContract
    protected FactoryContract(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        super("", contractAddress, web3j, credentials, gasProvider);
    }

    // Retrieve the total number of pairs in the DEX (allPairsLength)
    public RemoteCall<BigInteger> allPairsLength() {
        final Function function = new Function(
                "allPairsLength",
                Collections.emptyList(),
                Arrays.asList(new TypeReference<Uint256>() {})
        );
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    // Retrieve the pair address by index (allPairs)
    public RemoteCall<String> allPairs(BigInteger index) {
        final Function function = new Function(
                "allPairs",
                Arrays.asList(new Uint256(index)),
                Arrays.asList(new TypeReference<Address>() {})
        );
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    // Retrieve a specific pair address using tokenA and tokenB addresses
    public RemoteCall<String> getPair(String tokenA, String tokenB) {
        final Function function = new Function(
                "getPair",
                Arrays.asList(new Address(tokenA), new Address(tokenB)),
                Arrays.asList(new TypeReference<Address>() {})
        );
        return executeRemoteCallSingleValueReturn(function, String.class);
    }
}
