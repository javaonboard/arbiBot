package com.sniper.app.mind.contracts;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ArbitrageContract extends Contract {

    private static final String BINARY = "";  // Optional binary string for deployment, not required if already deployed

    public static ArbitrageContract load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        return new ArbitrageContract(contractAddress, web3j, credentials, gasProvider);
    }

    protected ArbitrageContract(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, credentials, gasProvider);
    }

    /**
     * Method to call the "checkAndExecuteArbitrage" function from the smart contract
     */
    public RemoteCall<TransactionReceipt> checkAndExecuteArbitrage(
            Address tokenA,
            Address tokenB,
            Uint256 amountIn,
            Uint256 gasPriceInWei,
            Address buyRouterAddress,
            Address sellRouterAddress) {
        final Function function = new Function(
                "checkAndExecuteArbitrage",  // Solidity function name
                Arrays.asList(tokenA, tokenB, amountIn, gasPriceInWei, buyRouterAddress, sellRouterAddress),
                Collections.emptyList()  // No return value (transaction receipt)
        );
        return executeRemoteCallTransaction(function);
    }

    /**
     * Withdraw tokens from the contract
     */
    public RemoteCall<TransactionReceipt> withdrawToken(Address token, Uint256 amount) {
        final Function function = new Function(
                "withdrawToken",
                Arrays.asList(token, amount),
                Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    /**
     * Withdraw ETH from the contract
     */
    public RemoteCall<TransactionReceipt> withdrawETH() {
        final Function function = new Function(
                "withdrawETH",
                Collections.emptyList(),
                Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    /**
     * Get the contract's ETH balance
     */
    public RemoteCall<Uint256> getContractBalance() {
        final Function function = new Function(
                "getContractBalance",
                Collections.emptyList(),
                Arrays.asList(new TypeReference<Uint256>() {})
        );
        return executeRemoteCallSingleValueReturn(function, Uint256.class);
    }
}
