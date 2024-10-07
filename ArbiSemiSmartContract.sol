// SPDX-License-Identifier: MIT
pragma solidity ^0.8.10;

interface IUniswapV2Router {
    function swapExactTokensForTokens(
        uint amountIn,
        uint amountOutMin,
        address[] calldata path,
        address to,
        uint deadline
    ) external returns (uint[] memory amounts);

    function getAmountsOut(uint amountIn, address[] calldata path) external view returns (uint[] memory amounts);
}

interface IERC20 {
    function transferFrom(address sender, address recipient, uint amount) external returns (bool);
    function approve(address spender, uint amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
    function allowance(address owner, address spender) external view returns (uint256);
}

contract ArbitrageJavanDynamicDEX {

    address public owner;
    uint public minProfit; // Minimum profit threshold in tokenB for a successful arbitrage

    /**
     * @dev Constructor function.
     * Hardcoded the minimum profit threshold to 0.01 tokenB.
     */
    constructor() {
        owner = msg.sender; // Set the deployer as the owner of the contract
        minProfit = 10000000000000000; // Hardcoded minimum profit of 0.01 tokenB (assuming 18 decimal places)
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner can execute this");
        _;
    }

    // Main function to check for arbitrage opportunities and execute the trade if profitable
    function checkAndExecuteArbitrage(
        address tokenA, 
        address tokenB, 
        uint amountIn, 
        uint gasPriceInWei, 
        address buyRouterAddress, // Buy from this DEX router
        address sellRouterAddress // Sell on this DEX router
    ) external onlyOwner {
        IUniswapV2Router buyRouter = IUniswapV2Router(buyRouterAddress);
        IUniswapV2Router sellRouter = IUniswapV2Router(sellRouterAddress);

        // Get prices from both DEXs
        uint priceOnBuyDEX = getAmountOut(buyRouter, tokenA, tokenB, amountIn);
        uint priceOnSellDEX = getAmountOut(sellRouter, tokenA, tokenB, amountIn);
        
        // Calculate gas costs for the arbitrage transaction
        uint gasEstimate = estimateGasForTrade(tokenA, tokenB, amountIn, buyRouter); // Updated function call
        uint gasCost = gasEstimate * gasPriceInWei;

        // Check if arbitrage is profitable after considering gas costs
        if (priceOnSellDEX > priceOnBuyDEX + minProfit + gasCost) {
            // Perform arbitrage: buy on buyRouter and sell on sellRouter
            executeArbitrage(tokenA, tokenB, amountIn, buyRouter, sellRouter);
        }
    }

    // Function to estimate gas cost for swapping tokens
    function estimateGasForTrade(address tokenA, address tokenB, uint amountIn, IUniswapV2Router buyRouter) public view returns (uint) {
        uint gasLeftBefore = gasleft();
        
        // Create a mock swap call to estimate gas usage using the actual input amount
        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        // Use the provided buyRouter and amountIn for gas estimation
        buyRouter.getAmountsOut(amountIn, path); 

        uint gasLeftAfter = gasleft();
        return gasLeftBefore - gasLeftAfter;
    }

    // Execute the arbitrage trade between two exchanges
    function executeArbitrage(
        address tokenA, 
        address tokenB, 
        uint amountIn, 
        IUniswapV2Router buyRouter, 
        IUniswapV2Router sellRouter
    ) internal {
        // Approve the router to spend tokens, only if necessary
        if (IERC20(tokenA).allowance(address(this), address(buyRouter)) < amountIn) {
            IERC20(tokenA).approve(address(buyRouter), amountIn);
        }
        
        // Define the swap path (TokenA -> TokenB)
        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        // Execute the buy on the first exchange
        uint[] memory amountsOut = buyRouter.getAmountsOut(amountIn, path);
        uint amountOutMin = amountsOut[1];

        buyRouter.swapExactTokensForTokens(
            amountIn, 
            amountOutMin, 
            path, 
            address(this), 
            block.timestamp + 300 // Set a deadline for the trade
        );

        // Now sell on the second exchange
        if (IERC20(tokenB).allowance(address(this), address(sellRouter)) < amountsOut[1]) {
            IERC20(tokenB).approve(address(sellRouter), amountsOut[1]);
        }

        address[] memory reversePath  = new address[](2);
        reversePath[0] = tokenB;
        reversePath[1] = tokenA;

        uint[] memory amountsOutFromSell = sellRouter.getAmountsOut(amountsOut[1], reversePath);
        sellRouter.swapExactTokensForTokens(
            amountsOut[1],
            amountsOutFromSell[1],
            reversePath,
            address(this),
            block.timestamp + 300
        );
    }

    // Helper function to get price of tokenA in terms of tokenB from a router
    function getAmountOut(IUniswapV2Router router, address tokenA, address tokenB, uint amountIn) internal view returns (uint) {
        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        uint[] memory amountsOut = router.getAmountsOut(amountIn, path);
        return amountsOut[1]; // Return the amount of tokenB received
    }

    // Function to withdraw tokens from the contract
    function withdrawToken(address _token, uint _amount) external onlyOwner {
        IERC20(_token).transferFrom(address(this), owner, _amount);
    }

    // Fallback function to receive BNB if needed
    receive() external payable {}

    // Function to withdraw BNB
    function withdrawBNB() external onlyOwner {
        payable(owner).transfer(address(this).balance);
    }
}
