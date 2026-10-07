package com.fakeprovider;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The provider's API as Tally sees it (brief section 6). Failure modes arrive with build step 7. */
@RestController
class ProviderController {

    record ChargeRequest(String token, long amount, String currency, String reference) {
    }

    record ChargeResponse(String chargeId, String reference, Charge.Outcome outcome) {
    }

    private final Ledger ledger;

    ProviderController(Ledger ledger) {
        this.ledger = ledger;
    }

    /** Simulates the hosted card form: the card stays with the provider, Tally gets a token (ADR-015). */
    @PostMapping("/tokens")
    Map<String, String> token() {
        return Map.of("token", ledger.issueToken());
    }

    @PostMapping("/charges")
    ChargeResponse charge(@RequestBody ChargeRequest request) {
        Charge charge = ledger.charge(request.token(), request.amount(), request.currency(), request.reference());
        return new ChargeResponse(charge.chargeId(), charge.reference(), charge.outcome());
    }

    /** The recorded truth for a reference. */
    @GetMapping("/charges")
    List<Charge> charges(@RequestParam String reference) {
        return ledger.chargesFor(reference);
    }

    /** Lets tests prove nobody was charged twice. */
    @GetMapping("/admin/charges/count")
    Map<String, Integer> count(@RequestParam String reference) {
        return Map.of("count", ledger.chargesFor(reference).size());
    }
}
