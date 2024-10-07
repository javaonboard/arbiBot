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

contract TriangularArbitrageJavan {

    address public owner;
    uint public minProfit; // Minimum profit threshold in tokenC for a successful arbitrage

    // Event declarations for logging success and failure
    event ArbitrageExecuted(address tokenA, address tokenB, address tokenC, uint amountIn, uint profit);
    event NoArbitrageOpportunity(address tokenA, address tokenB, address tokenC);

    /**
     * @dev Constructor function.
     * Hardcoded the minimum profit threshold to 0.01 tokenC.
     */
    constructor() {
        owner = msg.sender; // Set the deployer as the owner of the contract
        minProfit = 10000000000000000; // Hardcoded minimum profit of 0.01 tokenC (assuming 18 decimal places)
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner can execute this");
        _;
    }

    // Main function to check for arbitrage opportunities and execute the trade if profitable
    function checkAndExecuteTriangularArbitrage(
        address tokenA,
        address tokenB,
        address tokenC,
        uint amountIn,
        uint amountOutMinAtoB,
        uint amountOutMinBtoC,
        uint amountOutMinCtoA,
        address routerAddress // Typically within one DEX, but can support multiple DEXes
    ) external onlyOwner {
        IUniswapV2Router router = IUniswapV2Router(routerAddress);

        // Get prices for each step in the triangle (A -> B, B -> C, C -> A)
        uint amountOutTokenB = getAmountOut(router, tokenA, tokenB, amountIn);
        uint amountOutTokenC = getAmountOut(router, tokenB, tokenC, amountOutTokenB);
        uint finalAmountIn = getAmountOut(router, tokenC, tokenA, amountOutTokenC);

        // Check if arbitrage is still profitable after considering off-chain calculations
        if (finalAmountIn > amountIn + minProfit) {
            // Perform triangular arbitrage
            executeTriangularArbitrage(tokenA, tokenB, tokenC, amountIn, amountOutMinAtoB, amountOutMinBtoC, amountOutMinCtoA, router);
        } else {
            emit NoArbitrageOpportunity(tokenA, tokenB, tokenC);
        }
    }

    // Execute the triangular arbitrage trade (A -> B -> C -> A)
    function executeTriangularArbitrage(
        address tokenA, 
        address tokenB, 
        address tokenC, 
        uint amountIn, 
        uint amountOutMinAtoB,
        uint amountOutMinBtoC,
        uint amountOutMinCtoA,
        IUniswapV2Router router
    ) internal {
        // Store all tokens and amounts to use later in the event emission
        address initialTokenA = tokenA;
        address initialTokenB = tokenB;
        address initialTokenC = tokenC;
        uint initialAmountIn = amountIn;

        // Step 1: Swap tokenA -> tokenB
        approveTokenIfNeeded(tokenA, router, amountIn);

        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        router.swapExactTokensForTokens(
            amountIn,
            amountOutMinAtoB,
            path,
            address(this),
            block.timestamp + 300
        );

        uint amountReceivedTokenB = IERC20(tokenB).balanceOf(address(this));

        // Step 2: Swap tokenB -> tokenC
        approveTokenIfNeeded(tokenB, router, amountReceivedTokenB);

        path[0] = tokenB;
        path[1] = tokenC;

        router.swapExactTokensForTokens(
            amountReceivedTokenB,
            amountOutMinBtoC,
            path,
            address(this),
            block.timestamp + 300
        );

        uint amountReceivedTokenC = IERC20(tokenC).balanceOf(address(this));

        // Step 3: Swap tokenC -> tokenA
        approveTokenIfNeeded(tokenC, router, amountReceivedTokenC);

        path[0] = tokenC;
        path[1] = initialTokenA;

        router.swapExactTokensForTokens(
            amountReceivedTokenC,
            amountOutMinCtoA,
            path,
            address(this),
            block.timestamp + 300
        );

        uint finalAmountReceivedTokenA = IERC20(initialTokenA).balanceOf(address(this));

        // Conditional event emission based on profit
        if (finalAmountReceivedTokenA > initialAmountIn) {
            emit ArbitrageExecuted(
                initialTokenA,        // Use the stored tokenA
                initialTokenB,        // Use the stored tokenB
                initialTokenC,        // Use the stored tokenC
                initialAmountIn,       // Use initialAmountIn which is a stored variable
                finalAmountReceivedTokenA - initialAmountIn  // Only emit profit if there's a positive value
            );
        } else {
            emit NoArbitrageOpportunity(initialTokenA, initialTokenB, initialTokenC);
        }
    }

    // Helper function to get price of tokenA in terms of tokenB from a router
    function getAmountOut(IUniswapV2Router router, address tokenA, address tokenB, uint amountIn) internal view returns (uint) {
        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        uint[] memory amountsOut = router.getAmountsOut(amountIn, path);
        return amountsOut[1]; // Return the amount of tokenB received
    }

    // Helper function to approve tokens if necessary
    function approveTokenIfNeeded(address token, IUniswapV2Router router, uint amount) internal {
        if (IERC20(token).allowance(address(this), address(router)) < amount) {
            IERC20(token).approve(address(router), type(uint).max); // Approve max tokens
        }
    }

    // Function to withdraw tokens from the contract
    function withdrawToken(address _token, uint _amount) external onlyOwner {
        IERC20(_token).transferFrom(address(this), owner, _amount);
    }

    // Fallback function to receive ETH if needed
    receive() external payable {}

    // Function to withdraw ETH
    function withdrawETH() external onlyOwner {
        payable(owner).transfer(address(this).balance);
    }
}
