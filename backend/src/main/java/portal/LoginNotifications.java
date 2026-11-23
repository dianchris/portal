package portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class LoginNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(LoginNotifications.class);
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String serviceToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    LoginNotifications(JdbcTemplate db, Accounts accounts, ObjectMapper json,
            @Value("${portal.backstage-url}") String backstageUrl,
            @Value("${portal.backstage-token}") String serviceToken) {
        this.db = db;
        this.accounts = accounts;
        this.json = json;
        this.endpoint = URI.create(backstageUrl.replaceAll("/+$", "") + "/integration/v1/portal-login-events");
        this.serviceToken = serviceToken;
    }

    @PostConstruct
    void initialize() {
        db.execute("""
            CREATE TABLE IF NOT EXISTS portal_accounts.login_outbox (
              event_id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES portal_accounts.users(id),
              payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
              next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), delivered_at TIMESTAMPTZ
            );
            CREATE INDEX IF NOT EXISTS login_outbox_pending
              ON portal_accounts.login_outbox(next_attempt_at) WHERE delivered_at IS NULL;
            """);
    }

    @Transactional
    public Map<String,String> record(Authentication actor, UUID eventId) {
        if (accounts.passwordChangeRequired(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Complete password creation before signing in.");
        }
        var membership = accounts.membership(actor);
        var users = db.query("""
            SELECT u.id,r.reference_id FROM portal_accounts.users u
            JOIN portal_accounts.registrations r ON r.user_id=u.id
            WHERE u.email=? AND u.active=TRUE AND u.password_change_required=FALSE
            """, (rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("reference_id")),
            Accounts.normalize(actor.getName()));
        if (users.isEmpty()) return Map.of("status", "NOT_LINKED");
        UUID userId = users.getFirst().getKey();
        String payload;
        try {
            payload = json.writeValueAsString(Map.of(
                "eventId", eventId.toString(), "type", "PORTAL_LOGIN_SUCCEEDED",
                "referenceId", users.getFirst().getValue(), "userId", userId.toString(),
                "organizationId", membership.organizationId(), "email", Accounts.normalize(actor.getName()),
                "occurredAt", Instant.now().toString()));
        } catch (Exception error) {
            throw new IllegalStateException("Could not record Portal login", error);
        }
        db.update("""
            INSERT INTO portal_accounts.login_outbox(event_id,user_id,payload) VALUES (?,?,?)
            ON CONFLICT(event_id) DO NOTHING
            """, eventId, userId, payload);
        UUID owner = db.queryForObject("SELECT user_id FROM portal_accounts.login_outbox WHERE event_id=?", UUID.class, eventId);
        if (!userId.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Login event belongs to another account.");
        }
        return Map.of("status", "QUEUED");
    }

    private record Delivery(UUID id, String payload, int attempts) {}

    @Scheduled(fixedDelay=3000)
    @Transactional
    public void deliver() {
        var deliveries = db.query("""
            SELECT event_id,payload,attempts FROM portal_accounts.login_outbox
            WHERE delivered_at IS NULL AND next_attempt_at<=now()
            ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED
            """, (rs,n) -> new Delivery(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        for (var delivery : deliveries) {
            boolean sent = false;
            try {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + serviceToken).header("Content-Type", "application/json")
                    .header("Idempotency-Key", delivery.id().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.payload())).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                sent = status >= 200 && status < 300;
                if (!sent) LOG.warn("Backstage login notification {} returned HTTP {}", delivery.id(), status);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("Backstage login notification {} will be retried", delivery.id());
            }
            if (sent) {
                db.update("UPDATE portal_accounts.login_outbox SET delivered_at=now(),attempts=attempts+1 WHERE event_id=?", delivery.id());
            } else {
                int delay = Math.min(300, 3 * (1 << Math.min(delivery.attempts(), 7)));
                db.update("""
                    UPDATE portal_accounts.login_outbox SET attempts=attempts+1,
                    next_attempt_at=now()+(? * interval '1 second') WHERE event_id=?
                    """, delay, delivery.id());
            }
        }
    }
}
