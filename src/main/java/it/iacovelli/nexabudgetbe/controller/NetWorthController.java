package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.NetWorthDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.NetWorthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/net-worth")
@Tag(name = "Net worth", description = "Patrimonio netto: liquidità, crypto e investimenti")
@SecurityRequirement(name = "bearerAuth")
public class NetWorthController {

    private final NetWorthService netWorthService;

    public NetWorthController(NetWorthService netWorthService) {
        this.netWorthService = netWorthService;
    }

    @GetMapping
    @Operation(summary = "Patrimonio netto",
            description = "Liquidità dei conti + crypto + investimenti nella valuta indicata (default: valuta dell'utente). "
                    + "Una componente non calcolabile è null ed è segnalata in warnings")
    public ResponseEntity<NetWorthDto.NetWorthResponse> getNetWorth(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(netWorthService.getNetWorth(currentUser, currency));
    }

    @GetMapping("/history")
    @Operation(summary = "Storico del patrimonio",
            description = "Serie mensile di liquidità + crypto + investimenti (crypto e investimenti hanno storico solo dal primo snapshot giornaliero)")
    public ResponseEntity<NetWorthDto.NetWorthHistoryResponse> getHistory(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "12") int months,
            @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(netWorthService.getHistory(currentUser, months, currency));
    }
}
