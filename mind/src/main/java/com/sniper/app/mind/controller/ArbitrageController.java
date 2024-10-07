package com.sniper.app.mind.controller;



import com.sniper.app.mind.service.ArbitrageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ArbitrageController {

    @Autowired
    private ArbitrageService arbitrageService;

    @GetMapping("/start-arbitrage")
    public String startArbitrage() {
        try {
            arbitrageService.monitorArbitrageOnExchanges();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return "Arbitrage monitoring started!";
    }
}
