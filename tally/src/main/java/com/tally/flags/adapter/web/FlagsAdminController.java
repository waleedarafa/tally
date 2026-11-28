package com.tally.flags.adapter.web;

import com.tally.flags.FlagsAdminApi;
import com.tally.flags.FlagConflictException;
import com.tally.flags.FlagNotFoundException;
import com.tally.flags.InvalidFlagRequestException;
import com.tally.flags.FlagReport;
import com.tally.flags.FlagView;
import com.tally.platform.Caller;
import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import com.tally.platform.IdempotentEndpoints;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The back-office endpoints of the flags module (ADR-053): the report, and define, default, account and country
 * assignments, release and remove. Every change goes through the platform's shared idempotency mechanism (ADR-019,
 * CLAUDE.md rule 13), keyed by the staff user's own scope.
 */
@RestController
@RequestMapping("/v1/admin/flags")
class FlagsAdminController {

    record DefineBody(String name) {
    }

    record OnBody(Boolean on) {
    }

    record AdminChange(String operation, String flag, String target, Boolean on) {
    }

    record FlagChangeResponse(Instant at, String description) {
    }

    record FlagSummary(String name, boolean defaultOn, Map<UUID, Boolean> accounts, Map<String, Boolean> countries,
            Instant releasedAt, Instant removeBy, boolean overdue, int changeCount, Instant lastChangeAt) {
    }

    record FlagDetail(String name, boolean defaultOn, Map<UUID, Boolean> accounts, Map<String, Boolean> countries,
            Instant releasedAt, Instant removeBy, boolean overdue, int changeCount, Instant lastChangeAt,
            List<FlagChangeResponse> changes) {
    }

    record FlagReportResponse(Instant asOf, List<FlagSummary> flags, List<String> overdue) {
    }

    private final FlagsAdminApi admin;
    private final IdempotentEndpoints endpoints;
    private final IdempotencyStore keys;
    private final JsonMapper json;

    FlagsAdminController(FlagsAdminApi admin, IdempotentEndpoints endpoints, IdempotencyKeys idempotencyKeys,
            JsonMapper json) {
        this.admin = admin;
        this.endpoints = endpoints;
        this.keys = idempotencyKeys.register("flags.idempotency_key");
        this.json = json;
    }

    /** The flags report (ADR-010; ADR-053). */
    @GetMapping
    ResponseEntity<String> report(Caller caller) {
        try {
            FlagReport report = admin.report();
            return ok(new FlagReportResponse(report.asOf(), report.flags().stream().map(this::summary).toList(),
                    report.overdue()));
        } catch (RuntimeException e) {
            return refusalOrThrow(e);
        }
    }

    /** One flag with its full change log. */
    @GetMapping("/{name}")
    ResponseEntity<String> flag(@PathVariable String name, Caller caller) {
        try {
            return ok(detail(admin.flag(name)));
        } catch (RuntimeException e) {
            return refusalOrThrow(e);
        }
    }

    /** Defines a new flag: off by default, not yet released. */
    @PostMapping
    ResponseEntity<String> define(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) DefineBody request, Caller caller) {
        if (request == null || isBlank(request.name())) {
            // Not stored: it is not a flag change, and no key is claimed for it.
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a flag change",
                    "name is required.");
        }
        return endpoints.handle(keys, caller.keyScope(), key, request,
                () -> flagResponse(admin.define(request.name(), caller.id()), HttpStatus.CREATED), this::refusal);
    }

    /** Sets the default for everyone without an assignment. */
    @PutMapping("/{name}/default")
    ResponseEntity<String> setDefault(@PathVariable String name,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) OnBody request, Caller caller) {
        if (request == null || request.on() == null) {
            // Not stored: it is not a flag change, and no key is claimed for it.
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a flag change",
                    "on is required.");
        }
        return endpoints.handle(keys, caller.keyScope(), key, new AdminChange("set-default", name, null, request.on()),
                () -> flagResponse(admin.setDefault(name, request.on(), caller.id()), HttpStatus.OK), this::refusal);
    }

    /** Assigns the flag for one account. */
    @PutMapping("/{name}/accounts/{accountId}")
    ResponseEntity<String> assignAccount(@PathVariable String name, @PathVariable String accountId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) OnBody request, Caller caller) {
        if (request == null || request.on() == null) {
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a flag change",
                    "on is required.");
        }
        UUID id;
        try {
            id = UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a valid flag request",
                    "accountId must be a UUID.");
        }
        String account = id.toString();
        return endpoints.handle(keys, caller.keyScope(), key,
                new AdminChange("assign-account", name, account, request.on()),
                () -> flagResponse(admin.assignAccount(name, id, request.on(), caller.id()), HttpStatus.OK),
                this::refusal);
    }

    /** Clears the account assignment. */
    @DeleteMapping("/{name}/accounts/{accountId}")
    ResponseEntity<String> clearAccount(@PathVariable String name, @PathVariable String accountId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, Caller caller) {
        UUID id;
        try {
            id = UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a valid flag request",
                    "accountId must be a UUID.");
        }
        String account = id.toString();
        return endpoints.handle(keys, caller.keyScope(), key, new AdminChange("clear-account", name, account, null),
                () -> flagResponse(admin.clearAccount(name, id, caller.id()), HttpStatus.OK), this::refusal);
    }

    /** Assigns the flag for one country. */
    @PutMapping("/{name}/countries/{country}")
    ResponseEntity<String> assignCountry(@PathVariable String name, @PathVariable String country,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) OnBody request, Caller caller) {
        if (request == null || request.on() == null) {
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a flag change",
                    "on is required.");
        }
        String code = country.trim().toUpperCase(Locale.ROOT);
        return endpoints.handle(keys, caller.keyScope(), key,
                new AdminChange("assign-country", name, code, request.on()),
                () -> flagResponse(admin.assignCountry(name, code, request.on(), caller.id()), HttpStatus.OK),
                this::refusal);
    }

    /** Clears the country assignment. */
    @DeleteMapping("/{name}/countries/{country}")
    ResponseEntity<String> clearCountry(@PathVariable String name, @PathVariable String country,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, Caller caller) {
        String code = country.trim().toUpperCase(Locale.ROOT);
        return endpoints.handle(keys, caller.keyScope(), key,
                new AdminChange("clear-country", name, code, null),
                () -> flagResponse(admin.clearCountry(name, code, caller.id()), HttpStatus.OK), this::refusal);
    }

    /** Fully releases the flag. */
    @PostMapping("/{name}/release")
    ResponseEntity<String> release(@PathVariable String name,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, Caller caller) {
        return endpoints.handle(keys, caller.keyScope(), key, new AdminChange("release", name, null, null),
                () -> flagResponse(admin.release(name, caller.id()), HttpStatus.OK), this::refusal);
    }

    /** Removes the flag. */
    @DeleteMapping("/{name}")
    ResponseEntity<String> remove(@PathVariable String name,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, Caller caller) {
        return endpoints.handle(keys, caller.keyScope(), key, new AdminChange("remove", name, null, null),
                () -> {
                    admin.remove(name, caller.id());
                    return ResponseEntity.status(HttpStatus.NO_CONTENT).contentType(MediaType.APPLICATION_JSON).body("");
                }, this::refusal);
    }

    private ResponseEntity<String> flagResponse(FlagView view, HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(summary(view)));
    }

    private ResponseEntity<String> ok(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
    }

    private FlagSummary summary(FlagView view) {
        return new FlagSummary(view.name(), view.defaultOn(), view.accounts(), view.countries(), view.releasedAt(),
                view.removeBy(), view.overdue(), view.changeCount(), view.lastChangeAt());
    }

    private FlagDetail detail(FlagView view) {
        return new FlagDetail(view.name(), view.defaultOn(), view.accounts(), view.countries(), view.releasedAt(),
                view.removeBy(), view.overdue(), view.changeCount(), view.lastChangeAt(),
                view.changes().stream().map(change -> new FlagChangeResponse(change.at(), change.description()))
                        .toList());
    }

    private Optional<ResponseEntity<String>> refusal(RuntimeException refused) {
        return Optional.ofNullable(switch (refused) {
            case FlagNotFoundException e -> endpoints.problem(HttpStatus.NOT_FOUND, "about:blank", "No such flag",
                    e.getMessage());
            case FlagConflictException e -> endpoints.problem(HttpStatus.CONFLICT, "about:blank",
                    "The flags rules refuse this change", e.getMessage());
            case InvalidFlagRequestException e -> endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank",
                    "Not a valid flag request", e.getMessage());
            default -> null;
        });
    }

    private ResponseEntity<String> refusalOrThrow(RuntimeException refused) {
        return refusal(refused).orElseThrow(() -> refused);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
