package portal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/requests")
class Requests {
    enum Environment { SANDBOX, PRODUCTION }
    enum Scope { ACCOUNTS_READ, PAYMENTS_CREATE, PAYMENTS_READ }
    enum Method { CLIENT_SECRET, PUBLIC_KEY }
    enum Status { SUBMITTED, IN_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, ACTIVE }

    record Submission(
        @NotBlank @Size(min = 3, max = 60) String applicationName,
        @NotBlank @Size(max = 300) String description,
        @NotBlank @Size(max = 1000) String useCase,
        @NotBlank @Size(max = 100) String contactName,
        @NotBlank @Email @Size(max = 254) String contactEmail,
        @Size(max = 2048) String callbackUrl,
        @NotNull Environment environment,
        @NotEmpty @Size(max = 3) List<@NotNull Scope> scopes,
        @NotNull Method credentialMethod,
        @Size(max = 8000) String publicKey
    ) {}

    // These types are an allowlist. Backstage response bodies are never relayed.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestView(UUID id, String applicationName, String description, Environment environment,
                       List<Scope> scopes, Method credentialMethod, Status status, Instant updatedAt,
                       List<Comment> comments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Comment(String author, Instant createdAt, String text) {}

    private final RestClient backstage;
    private final Accounts accounts;

    Requests(@Value("${portal.backstage-url}") String url, @Value("${portal.backstage-token}") String token,
             Accounts accounts) {
        if (token.isBlank()) throw new IllegalArgumentException("Backstage configuration is required");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.backstage = RestClient.builder().baseUrl(url).requestFactory(factory)
            .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        this.accounts = accounts;
    }

    @GetMapping("/access")
    java.util.Map<String, Object> access(Authentication actor) {
        var membership = accounts.membership(actor);
        return java.util.Map.of("productionAccess", membership.productionAccess(),
            "organizationId", membership.organizationId(), "companyName", membership.companyName(),
            "role", membership.role(), "canSubmit", true,
            "registrationSource", membership.registrationSource(), "defaultEnvironment", membership.defaultEnvironment());
    }

    private void requireAccess(Authentication actor, Environment environment) {
        if (environment == Environment.PRODUCTION && !accounts.membership(actor).productionAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Production access is required.");
        }
    }

    @GetMapping("/onboarding")
    java.util.Map<String, Boolean> onboarding(Authentication actor) {
        String organization = accounts.membership(actor).organizationId();
        // Only expose whether onboarding has started, including for Sandbox-only users.
        // Production application details remain protected by the list/detail permission checks.
        boolean hasApplications = !applicationRequests(organization, Environment.SANDBOX).isEmpty()
            || !applicationRequests(organization, Environment.PRODUCTION).isEmpty();
        return java.util.Map.of("hasApplications", hasApplications);
    }

    @GetMapping
    List<RequestView> list(@RequestParam Environment environment, Authentication actor) {
        String organization = accounts.membership(actor).organizationId();
        requireAccess(actor, environment);
        return applicationRequests(organization, environment);
    }

    private List<RequestView> applicationRequests(String organization, Environment environment) {
        List<RequestView> requests = pull(() -> backstage.get()
            .uri("/partner/v1/organizations/{org}/requests?environment={env}", organization, environment)
            .retrieve().body(new ParameterizedTypeReference<List<RequestView>>() {}));
        return requests.stream().filter(request -> request.environment() == environment).toList();
    }

    @GetMapping("/{id}")
    RequestView detail(@PathVariable UUID id, Authentication actor) {
        String organization = accounts.membership(actor).organizationId();
        RequestView request = pull(() -> backstage.get().uri("/partner/v1/organizations/{org}/requests/{id}", organization, id)
            .retrieve().body(RequestView.class));
        requireAccess(actor, request.environment());
        return request;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, headers = "X-Portal-Request=1")
    ResponseEntity<RequestView> submit(@RequestHeader("Idempotency-Key") UUID key,
                                       @Valid @RequestBody Submission input, Authentication actor) {
        String organization = accounts.membership(actor).organizationId();
        requireAccess(actor, input.environment());
        validate(input);
        RequestView created = pull(() -> backstage.post().uri("/partner/v1/organizations/{org}/requests", organization)
            .header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).body(input)
            .retrieve().body(RequestView.class));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private static void validate(Submission input) {
        if (input.scopes().stream().distinct().count() != input.scopes().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate scope");
        }
        if (input.environment() == Environment.PRODUCTION && input.credentialMethod() != Method.PUBLIC_KEY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Production requires a customer-managed public key");
        }
        String key = input.publicKey();
        if (input.credentialMethod() == Method.PUBLIC_KEY) {
            if (key == null || !key.strip().matches("-----BEGIN PUBLIC KEY-----\\s+[A-Za-z0-9+/=\\r\\n]+\\s+-----END PUBLIC KEY-----")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a PEM public key");
            }
        } else if (key != null && !key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public key does not match the selected credential method");
        }
        if (input.callbackUrl() == null || input.callbackUrl().isBlank()) return;
        try {
            URI uri = URI.create(input.callbackUrl());
            boolean localSandbox = input.environment() == Environment.SANDBOX
                && "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
            if (uri.getHost() != null && uri.getUserInfo() == null
                && ("https".equals(uri.getScheme()) || localSandbox)) return;
        } catch (IllegalArgumentException ignored) {
            // Report invalid callback syntax with the same validation message.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid callback URL");
    }

    private static <T> T pull(java.util.function.Supplier<T> operation) {
        try {
            T result = operation.get();
            if (result != null) return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (e.getStatusCode().value() == 409) throw new ResponseStatusException(HttpStatus.CONFLICT);
        } catch (RestClientException ignored) {
            // Keep upstream response bodies and infrastructure details internal.
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<java.util.Map<String, String>> error(ResponseStatusException error) {
        String message = error.getReason();
        if (message == null) message = switch (error.getStatusCode().value()) {
            case 404 -> "Request not found.";
            case 409 -> "Backstage reported a conflicting request.";
            default -> "Backstage is unavailable. Try again.";
        };
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("message", message));
    }
}
