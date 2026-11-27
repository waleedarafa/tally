package com.tallyaccess.web;

import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.BundleFact;
import com.tallyaccess.partner.InvalidNoticeException;
import com.tallyaccess.partner.PartnerNotice;
import com.tallyaccess.partner.PartnerNoticeTranslator;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /v1/partners/{partnerId}/notices (ADR-056). */
@RestController
class PartnerNoticeController {

    private final AccessCopy copy;

    PartnerNoticeController(AccessCopy copy) {
        this.copy = copy;
    }

    @PostMapping("/v1/partners/{partnerId}/notices")
    ResponseEntity<?> notice(@PathVariable String partnerId, @RequestBody PartnerNotice notice) {
        if (notice != null && !partnerId.equals(notice.partnerId())) {
            return ResponseEntity.badRequest().body(Map.of("reason", "partnerId does not match the path"));
        }
        BundleFact fact;
        try {
            fact = PartnerNoticeTranslator.translate(notice);
        } catch (InvalidNoticeException e) {
            return ResponseEntity.badRequest().body(Map.of("reason", e.getMessage()));
        }
        copy.applyBundle(fact);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
