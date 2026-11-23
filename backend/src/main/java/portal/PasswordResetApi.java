package portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PasswordResetApi {
    record Issuance(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,150}") String referenceId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,150}") String registrationReference,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,150}") String organizationId,
        @NotBlank @Email @Size(max=254) String email
    ) {}
    record Token(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    record Completion(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Size(min=12,max=64) String password
    ) {}
    private final Accounts accounts;
    private final PasswordResets resets;

    PasswordResetApi(Accounts accounts,PasswordResets resets) {
        this.accounts=accounts;
        this.resets=resets;
    }

    @PostMapping(path="/internal/v1/password-resets",consumes="application/json")
    PasswordResets.Issued issue(@RequestHeader(value="Authorization",required=false) String authorization,
                               @Valid @RequestBody Issuance input) {
        accounts.serviceAccess(authorization);
        return resets.issue(input);
    }

    @GetMapping("/internal/v1/password-resets/{reference}")
    PasswordResets.Progress progress(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @PathVariable String reference) {
        accounts.serviceAccess(authorization);
        return resets.progress(reference);
    }

    @PostMapping(path="/api/password-resets/inspect",consumes="application/json",headers="X-Portal-Request=1")
    PasswordResets.Preview inspect(@Valid @RequestBody Token input) { return resets.inspect(input.token()); }

    @PostMapping(path="/api/password-resets/complete",consumes="application/json",headers="X-Portal-Request=1")
    Map<String,String> complete(@Valid @RequestBody Completion input) {
        resets.complete(input);
        return Map.of("status","COMPLETED");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()));
    }
}
