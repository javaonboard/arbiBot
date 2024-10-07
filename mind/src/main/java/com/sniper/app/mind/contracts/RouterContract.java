package com.sniper.app.mind.contracts;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.TypeReference;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.tx.Contract;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.exceptions.ContractCallException;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigDecimal;
import java.util.List;

public class RouterContract extends Contract {

    protected RouterContract(String contractAddress, Web3j web3j, ReadonlyTransactionManager transactionManager, ContractGasProvider gasProvider) {
        super("", contractAddress, web3j, transactionManager, gasProvider);
    }

    // Load method to create an instance of RouterContract
    public static RouterContract load(String contractAddress, Web3j web3j, ReadonlyTransactionManager transactionManager, ContractGasProvider gasProvider) {
        return new RouterContract(contractAddress, web3j, transactionManager, gasProvider);
    }

    // Method to retrieve token price based on the amount of tokenIn and the path of the trade
    public RemoteCall<List<Uint256>> getAmountsOut(Uint256 amountIn, List<Address> path, String uuid) {
        final Function function = new Function(
                "getAmountsOut",
                List.of(amountIn, new DynamicArray<>(Address.class, path)),
                List.of(new TypeReference<DynamicArray<Uint256>>() {})
        );

        return new RemoteCall<>(() -> {
            try {
                // Execute the call and get the result as a list of Types
                List<Type> result = executeRemoteCallMultipleValueReturn(function).send();

                // Ensure the result contains a single DynamicArray<Uint256>
                if (result.size() == 1 && result.get(0) instanceof DynamicArray) {
                    DynamicArray<Uint256> amountsOut = (DynamicArray<Uint256>) result.get(0);

                    // Convert amounts to human-readable format and log
                    logHumanReadableAmounts(amountIn, path, amountsOut, uuid);

                    return amountsOut.getValue();
                } else {
                    throw new ClassCastException("Expected a single DynamicArray<Uint256>, but got: " + result);
                }
            } catch (ContractCallException e) {
                handleContractCallException(e);
                throw e;
            } catch (Exception e) {
                System.err.println("An unexpected error occurred: " + e.getMessage());
                throw e;
            }
        });
    }

    private void logHumanReadableAmounts(Uint256 amountIn, List<Address> path, DynamicArray<Uint256> amountsOut, String uuid) {
        BigDecimal inputAmountReadable = toHumanReadableAmount(amountIn, 18);
        BigDecimal outputAmountReadable = toHumanReadableAmount(amountsOut.getValue().get(amountsOut.getValue().size() - 1), 18);

        System.out.println(uuid + " Router Input amount for path " + path.toString() + ": " + inputAmountReadable);
        System.out.println(uuid + " Router Output amount for path " + path.toString() + ": " + outputAmountReadable);
    }

    private void handleContractCallException(ContractCallException e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.contains("execution reverted")) {
            String[] parts = errorMessage.split(":");
            String revertReason = parts[parts.length - 1].trim();
            System.err.println("Contract call reverted with reason: " + revertReason);
        } else {
            System.err.println("Contract call failed with unknown reason.");
        }
    }

    public static BigDecimal toHumanReadableAmount(Uint256 amount, int decimals) {
        BigDecimal rawValue = new BigDecimal(amount.getValue());
        return rawValue.divide(BigDecimal.TEN.pow(decimals));
    }
}
