import asyncio
from web3 import Web3
import logging
import time
from functools import lru_cache
from web3.middleware import geth_poa_middleware

# Setup logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

# Connect to BSC
bsc = Web3(Web3.HTTPProvider('https://bsc-dataseed.binance.org/'))

# Inject middleware for batch processing and fast syncing with BSC network
bsc.middleware_onion.inject(geth_poa_middleware, layer=0)

# DEX Router contracts and their addresses (e.g., PancakeSwap, BakerySwap)
DEX_ROUTERS = {
    'PancakeSwap': {
        'address': '0x...',  # Replace with PancakeSwap Router address
        'abi': '...'  # Replace with PancakeSwap Router ABI
    },
    'BakerySwap': {
        'address': '0x...',  # Replace with BakerySwap Router address
        'abi': '...'  # Replace with BakerySwap Router ABI
    },
    # Add more DEXs if necessary
}

# Standard Pair Contract ABI for Uniswap-based DEXs
pair_contract_abi = [
    {
        "constant": True,
        "inputs": [],
        "name": "token0",
        "outputs": [{"name": "", "type": "address"}],
        "payable": False,
        "stateMutability": "view",
        "type": "function"
    },
    {
        "constant": True,
        "inputs": [],
        "name": "token1",
        "outputs": [{"name": "", "type": "address"}],
        "payable": False,
        "stateMutability": "view",
        "type": "function"
    },
    {
        "constant": True,
        "inputs": [],
        "name": "getReserves",
        "outputs": [
            {"name": "_reserve0", "type": "uint112"},
            {"name": "_reserve1", "type": "uint112"},
            {"name": "_blockTimestampLast", "type": "uint32"}
        ],
        "payable": False,
        "stateMutability": "view",
        "type": "function"
    }
]

# PancakeSwap Factory contract (holds pairs)
factory_address = Web3.toChecksumAddress("0xca143ce32fe78f1f7019d7d551a6402fc5350c73")
factory_abi = '[...]'  # Replace with the PancakeSwap Factory ABI

# Setup Web3 contract instances for each DEX and the factory
dex_contracts = {
    dex_name: bsc.eth.contract(address=Web3.toChecksumAddress(dex_info['address']), abi=dex_info['abi'])
    for dex_name, dex_info in DEX_ROUTERS.items()
}

factory_contract = bsc.eth.contract(address=factory_address, abi=factory_abi)

# Contract ABI for your ArbitrageJavanDynamicDEX contract
solidity_contract_abi = [
    {
        "inputs": [
            {"internalType": "address", "name": "tokenA", "type": "address"},
            {"internalType": "address", "name": "tokenB", "type": "address"},
            {"internalType": "uint256", "name": "amountIn", "type": "uint256"},
            {"internalType": "uint256", "name": "gasPriceInWei", "type": "uint256"},
            {"internalType": "address", "name": "buyRouterAddress", "type": "address"},
            {"internalType": "address", "name": "sellRouterAddress", "type": "address"}
        ],
        "name": "checkAndExecuteArbitrage",
        "outputs": [],
        "stateMutability": "nonpayable",
        "type": "function"
    }
]

# Replace with your deployed smart contract address
contract_address = Web3.toChecksumAddress("0xYourContractAddress")
arbitrage_contract = bsc.eth.contract(address=contract_address, abi=solidity_contract_abi)


# Caching token addresses for pair contracts to avoid redundant Web3 calls
@lru_cache(maxsize=100)
def get_token_addresses(pair_contract):
    tokenA = pair_contract.functions.token0().call()
    tokenB = pair_contract.functions.token1().call()
    return tokenA, tokenB


async def get_all_pairs(factory_contract, limit=50):
    """
    Fetches all token pairs from the PancakeSwap factory contract, limited to a certain number of pairs.
    """
    pair_count = factory_contract.functions.allPairsLength().call()
    logging.info(f"Total number of pairs available: {pair_count}")

    token_pairs = []

    # Fetch token pairs (limited to 'limit' for performance reasons)
    for i in range(min(pair_count, limit)):
        pair_address = factory_contract.functions.allPairs(i).call()
        logging.info(f"Found pair: {pair_address}")

        # Create a contract instance for the pair using the standard ABI
        pair_contract = bsc.eth.contract(address=pair_address, abi=pair_contract_abi)
        tokenA, tokenB = get_token_addresses(pair_contract)
        token_pairs.append((tokenA, tokenB))

    return token_pairs


# Fetch gas price from BSC
def fetch_gas_price():
    return bsc.eth.gasPrice


# Asynchronous function to call the Solidity contract's checkAndExecuteArbitrage function
async def trigger_arbitrage(tokenA, tokenB, amount_in, buy_dex, sell_dex, private_key, account_address):
    """
    Executes the `checkAndExecuteArbitrage` function on the Solidity contract.
    """
    try:
        gas_price = fetch_gas_price()

        # Build the transaction
        transaction = arbitrage_contract.functions.checkAndExecuteArbitrage(
            tokenA,  # Pass dynamic tokenA
            tokenB,  # Pass dynamic tokenB
            Web3.toWei(amount_in, 'ether'),  # Amount in tokenA
            gas_price,  # Pass gas price
            buy_dex,  # Pass the buy DEX router address
            sell_dex  # Pass the sell DEX router address
        ).buildTransaction({
            'from': account_address,
            'gas': 2000000,  # Set an appropriate gas limit
            'gasPrice': gas_price,
            'nonce': bsc.eth.getTransactionCount(account_address),
        })

        # Sign the transaction
        signed_tx = bsc.eth.account.sign_transaction(transaction, private_key)

        # Send the transaction to the blockchain
        tx_hash = bsc.eth.sendRawTransaction(signed_tx.rawTransaction)

        # Wait for transaction receipt
        receipt = bsc.eth.waitForTransactionReceipt(tx_hash)
        logging.info(f"Arbitrage transaction successful, Tx Hash: {tx_hash.hex()}")

    except Exception as e:
        logging.error(f"Error executing arbitrage: {e}")


async def get_token_price(router, tokenA, tokenB):
    """
    Async function to get the token price from the router.
    """
    start_time = time.time()
    try:
        amounts = await asyncio.to_thread(
            router.functions.getAmountsOut(Web3.toWei(1, 'ether'), [tokenA, tokenB]).call
        )
        price = amounts[-1]
        logging.info(f"Price fetched for {tokenA}/{tokenB}: {price}")
        return price
    except Exception as e:
        logging.error(f"Error fetching price: {e}")
        return None
    finally:
        end_time = time.time()
        logging.info(f"Token price fetched in {end_time - start_time:.2f} seconds")


async def execute_arbitrage(buy_dex, sell_dex, tokenA, tokenB, best_buy_price, best_sell_price, private_key,
                            account_address, slippage_percent):
    """
    Async function to execute arbitrage between two DEXs with gas fees and slippage handling.
    """
    try:
        # Calculate potential profit and gas fees
        profit = best_sell_price - best_buy_price
        profit_in_ether = Web3.fromWei(profit, 'ether')

        # Estimate gas cost
        gas_estimate = await estimate_gas_cost({
            'from': account_address,
            'to': buy_dex,
            'data': ''  # Transaction data (fill in for buy/sell operations)
        })

        gas_cost_in_ether = Web3.fromWei(gas_estimate, 'ether')
        logging.info(f"Estimated gas cost: {gas_cost_in_ether} BNB")

        # Ensure the profit is greater than the gas fees
        if profit_in_ether <= gas_cost_in_ether:
            logging.info(
                f"Arbitrage not profitable due to gas fees. Profit: {profit_in_ether} BNB, Gas Cost: {gas_cost_in_ether} BNB")
            return

        # Handle slippage (set minimum output amount)
        min_sell_amount = await calculate_slippage(best_sell_price, slippage_percent)
        logging.info(f"Slippage: {slippage_percent * 100}%, Minimum sell amount: {min_sell_amount}")

        # Call the smart contract to execute arbitrage
        await trigger_arbitrage(tokenA, tokenB, Web3.toWei(1, 'ether'), buy_dex, sell_dex, private_key, account_address)

    except Exception as e:
        logging.error(f"Error executing arbitrage: {e}")


async def monitor_pairs_for_arbitrage(token_pairs, private_key, account_address, min_profit_margin, slippage_percent):
    """
    Monitor multiple token pairs for arbitrage opportunities across DEXs.
    """
    tasks = []
    for tokenA, tokenB in token_pairs:
        tasks.append(asyncio.create_task(
            monitor_pair(tokenA, tokenB, private_key, account_address, min_profit_margin, slippage_percent)))

    await asyncio.gather(*tasks)


async def monitor_pair(tokenA, tokenB, private_key, account_address, min_profit_margin, slippage_percent):
    """
    Monitor a single token pair for arbitrage opportunities across DEXs.
    """
    logging.info(f"Monitoring pair {tokenA} - {tokenB}")
    best_buy_price, best_buy_dex = await get_token_price(dex_contracts['PancakeSwap'], tokenA, tokenB)
    best_sell_price, best_sell_dex = await get_token_price(dex_contracts['BakerySwap'], tokenB, tokenA)

    # Check for arbitrage opportunity
    if best_buy_dex and best_sell_dex and best_sell_price > best_buy_price * (1 + min_profit_margin):
        logging.info(f"Arbitrage opportunity found for {tokenA} - {tokenB} between {best_buy_dex} and {best_sell_dex}")
        await execute_arbitrage(best_buy_dex, best_sell_dex, tokenA, tokenB, best_buy_price, best_sell_price,
                                private_key, account_address, slippage_percent)
    else:
        logging.info(f"No arbitrage opportunity for {tokenA} - {tokenB}")

    await asyncio.sleep(2)  # Optimized delay between monitoring cycles


async def main():
    # Replace these with your actual private key and address
    private_key = 'your_private_key'
    account_address = 'your_account_address'
    min_profit_margin = 0.01  # Example: 1% minimum profit margin
    slippage_percent = 0.005  # Example: 0.5% slippage

    # Fetch token pairs from PancakeSwap factory automatically
    token_pairs = await get_all_pairs(factory_contract, limit=100)  # Limit to first 50 pairs

    # Monitor token pairs for arbitrage opportunities
    await monitor_pairs_for_arbitrage(token_pairs, private_key, account_address, min_profit_margin, slippage_percent)


# Run the async main function
if __name__ == "__main__":
    asyncio.run(main())
