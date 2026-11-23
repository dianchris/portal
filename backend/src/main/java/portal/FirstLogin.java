package portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/account")
class FirstLogin {
    private final Accounts accounts;
    private final LoginNotifications notifications;
    FirstLogin(Accounts accounts, LoginNotifications notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
    }

    @PostMapping(path="/login-success", headers="X-Portal-Request=1")
    Map<String,String> loginSuccess(Authentication actor, @RequestHeader("Idempotency-Key") java.util.UUID eventId) {
        return notifications.record(actor, eventId);
    }

    record Password(@NotBlank @Size(min=12,max=64) String password) {}

    @GetMapping("/status")
    Map<String, Object> status(Authentication actor) {
        return Map.of("email", actor.getName(), "passwordChangeRequired", accounts.passwordChangeRequired(actor));
    }

    @PostMapping(path="/password", consumes="application/json", headers="X-Portal-Request=1")
    Map<String, String> change(Authentication actor, @Valid @RequestBody Password input) {
        accounts.changeInitialPassword(actor, input.password());
        return Map.of("status", "ACTIVE");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }
}
