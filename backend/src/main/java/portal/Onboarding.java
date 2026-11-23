package portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Onboarding {
    record Registration(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,150}") String referenceId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,150}") String organizationId,
        @NotBlank @Size(max=200) String companyName,
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=150) String name
    ) {}
    record Token(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    record Acceptance(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=12,max=64) String password
    ) {}
    private final Accounts accounts;

    Onboarding(Accounts accounts) { this.accounts = accounts; }

    @PostMapping(path="/internal/v1/registrations", consumes="application/json")
    Accounts.Registered register(@RequestHeader(value="Authorization", required=false) String authorization,
                                 @Valid @RequestBody Registration input) {
        accounts.serviceAccess(authorization);
        return accounts.register(input);
    }

    @GetMapping("/internal/v1/registrations/{reference}")
    Accounts.Progress progress(@RequestHeader(value="Authorization", required=false) String authorization,
                               @PathVariable String reference) {
        accounts.serviceAccess(authorization);
        return accounts.progress(reference);
    }

    @PostMapping(path="/api/onboarding/inspect", consumes="application/json", headers="X-Portal-Request=1")
    Accounts.Invitation inspect(@Valid @RequestBody Token input) {
        return accounts.inspect(input.token());
    }

    @PostMapping(path="/api/onboarding/accept", consumes="application/json", headers="X-Portal-Request=1")
    Map<String,String> accept(@Valid @RequestBody Acceptance input) {
        accounts.accept(input);
        return Map.of("status", "ACTIVE");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }
}
