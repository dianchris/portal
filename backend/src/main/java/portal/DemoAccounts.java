package portal;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DemoAccounts {
    record Sample(String username, String password, String label, boolean productionAccess) {}

    static final List<Sample> ACCOUNTS = List.of(
        new Sample("sample-production", "DemoProduction123!", "Sandbox and Production access", true),
        new Sample("sample-sandbox", "DemoSandbox123!", "Sandbox access only", false)
    );

    private final boolean enabled;

    DemoAccounts(@Value("${PORTAL_DEMO_ACCOUNTS_ENABLED:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @GetMapping("/api/demo/accounts")
    List<Sample> samples() { return enabled ? ACCOUNTS : List.of(); }
}
