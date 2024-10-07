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
    function transfer(address recipient, uint amount) external returns (bool);
}

interface ILendingPool {
    function flashLoan(
        address receiverAddress,
        address[] calldata assets,
        uint256[] calldata amounts,
        uint256[] calldata modes,
        address onBehalfOf,
        bytes calldata params,
        uint16 referralCode
    ) external;
}

interface IFlashLoanReceiver {
    function executeOperation(
        address[] calldata assets,
        uint256[] calldata amounts,
        uint256[] calldata premiums,
        address initiator,
        bytes calldata params
    ) external returns (bool);
}

contract TriangularArbitrageJavan is IFlashLoanReceiver {
    address public owner;
    ILendingPool public lendingPool;
    uint public minProfit; // Minimum profit threshold in tokenC for a successful arbitrage

    event ArbitrageExecuted(address tokenA, address tokenB, address tokenC, uint amountIn, uint profit);
    event NoArbitrageOpportunity(address tokenA, address tokenB, address tokenC);

    constructor(address _lendingPoolAddress) {
        owner = msg.sender;
        lendingPool = ILendingPool(_lendingPoolAddress); // Set the Aave lending pool address
        minProfit = 10000000000000000; // Hardcoded minimum profit of 0.01 tokenC (assuming 18 decimal places)
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner can execute this");
        _;
    }

    // Request a flash loan from Aave
    function executeFlashLoan(address tokenA, uint amountIn, address routerAddress) external onlyOwner {
        address receiverAddress = address(this);
        address[] memory assets = new address[](1);
        assets[0] = tokenA;

        uint256[] memory amounts = new uint256[](1);
        amounts[0] = amountIn;

        uint256[] memory modes = new uint256[](1);
        modes[0] = 0; // 0 = no debt, full repayment required

        bytes memory params = abi.encode(routerAddress); // Encodes parameters to pass to executeOperation

        lendingPool.flashLoan(receiverAddress, assets, amounts, modes, address(this), params, 0);
    }

    // This function is called by Aave after the loan is given
    function executeOperation(
        address[] calldata assets,
        uint256[] calldata amounts,
        uint256[] calldata premiums,
        address initiator,
        bytes calldata params
    ) external override returns (bool) {
        require(msg.sender == address(lendingPool), "Caller must be lending pool");

        (address routerAddress) = abi.decode(params, (address)); // Decode parameters

        address tokenA = assets[0];
        address tokenB; // Define tokenB for the arbitrage
        address tokenC; // Define tokenC for the arbitrage
        uint amountIn = amounts[0];

        // Perform the triangular arbitrage
        checkAndExecuteTriangularArbitrage(tokenA, tokenB, tokenC, amountIn, 0, 0, 0, routerAddress);

        // Repay the flash loan with the premium
        uint256 totalDebt = amounts[0] + premiums[0];
        IERC20(tokenA).approve(address(lendingPool), totalDebt);

        return true;
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
        address routerAddress
    ) internal {
        IUniswapV2Router router = IUniswapV2Router(routerAddress);

        // Get prices for each step in the triangle (A -> B, B -> C, C -> A)
        uint amountOutTokenB = getAmountOut(router, tokenA, tokenB, amountIn);
        uint amountOutTokenC = getAmountOut(router, tokenB, tokenC, amountOutTokenB);
        uint finalAmountIn = getAmountOut(router, tokenC, tokenA, amountOutTokenC);

        // Check if arbitrage is profitable
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
        path[1] = tokenA;

        router.swapExactTokensForTokens(
            amountReceivedTokenC,
            amountOutMinCtoA,
            path,
            address(this),
            block.timestamp + 300
        );

        uint finalAmountReceivedTokenA = IERC20(tokenA).balanceOf(address(this));

        // Conditional event emission based on profit
        if (finalAmountReceivedTokenA > amountIn) {
            emit ArbitrageExecuted(tokenA, tokenB, tokenC, amountIn, finalAmountReceivedTokenA - amountIn);
        } else {
            emit NoArbitrageOpportunity(tokenA, tokenB, tokenC);
        }
    }

    // Helper function to get price of tokenA in terms of tokenB from a router
    function getAmountOut(IUniswapV2Router router, address tokenA, address tokenB, uint amountIn) internal view returns (uint) {
        address[] memory path = new address[](2);
        path[0] = tokenA;
        path[1] = tokenB;

        uint[] memory amountsOut = router.getAmountsOut(amountIn, path);
        return amountsOut[1];
    }

    // Helper function to approve tokens if necessary
    function approveTokenIfNeeded(address token, IUniswapV2Router router, uint amount) internal {
        if (IERC20(token).allowance(address(this), address(router)) < amount) {
            IERC20(token).approve(address(router), type(uint).max);
        }
    }

    // Function to withdraw tokens from the contract
    function withdrawToken(address _token, uint _amount) external onlyOwner {
        IERC20(_token).transfer(owner, _amount);
    }

    // Function to withdraw all tokens of a specific ERC20 from the contract
    function withdrawAllTokens(address _token) external onlyOwner {
        uint balance = IERC20(_token).balanceOf(address(this));
        require(balance > 0, "No tokens to withdraw");
        IERC20(_token).transfer(owner, balance);
    }

    // Function to withdraw ETH
    function withdrawETH() external onlyOwner {
        payable(owner).transfer(address(this).balance);
    }

    // Fallback function to receive ETH
    receive() external payable {}
}
