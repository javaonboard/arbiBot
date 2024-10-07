package com.sniper.app.mind.contracts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;

import java.math.BigInteger;
import java.util.Collections;

public class ERC20Contract extends Contract {
    private static final Logger logger = LoggerFactory.getLogger(ERC20Contract.class);
    public static final String BINARY = "";

    // Existing constructor
    protected ERC20Contract(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    // New constructor for read-only operations
    protected ERC20Contract(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    // Existing load method using credentials
    public static ERC20Contract load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new ERC20Contract(contractAddress, web3j, credentials, contractGasProvider);
    }

    // New load method using a TransactionManager for read-only operations
    public static ERC20Contract load(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new ERC20Contract(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    // Method to fetch the token balance of an address
    public RemoteCall<Uint256> balanceOf(String address) {
        final Function function = new Function(
                "balanceOf",
                Collections.singletonList(new Address(address)),
                Collections.singletonList(new TypeReference<Uint256>() {})
        );
        return executeRemoteCallSingleValueReturn(function, Uint256.class);
    }

    // Method to fetch the decimals of the token
    public RemoteCall<Uint256> decimals() {
        final Function function = new Function(
                "decimals",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}) // Change to Uint256
        );
        return executeRemoteCallSingleValueReturn(function, Uint256.class);
    }
}
