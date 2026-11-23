package portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PasswordResets {
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final String publicUrl;
    private final SecureRandom random = new SecureRandom();

    PasswordResets(JdbcTemplate db, Accounts accounts, PasswordEncoder passwords, ObjectMapper json,
                   @Value("${portal.public-url}") String publicUrl) {
        this.db = db;
        this.accounts = accounts;
        this.passwords = passwords;
        this.json = json;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @PostConstruct
    void initialize() {
        db.execute("""
            CREATE TABLE IF NOT EXISTS portal_accounts.password_resets (
              reference_id TEXT PRIMARY KEY, reset_id UUID UNIQUE NOT NULL,
              user_id UUID NOT NULL REFERENCES portal_accounts.users(id),
              registration_reference TEXT NOT NULL REFERENCES portal_accounts.registrations(reference_id),
              fingerprint TEXT NOT NULL, email TEXT NOT NULL,
              token_hash TEXT UNIQUE NOT NULL, token_encrypted TEXT NOT NULL,
              expires_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ
            );
            CREATE INDEX IF NOT EXISTS password_resets_user ON portal_accounts.password_resets(user_id);
            """);
    }

    private record Row(String referenceId, UUID resetId, UUID userId, String fingerprint,
                       String email, String encrypted, Instant expiresAt, Instant completedAt) {}
    private record Identity(UUID userId, String organizationId, String email, boolean active,
                            boolean passwordChangeRequired, String companyName, String passwordHash) {}

    record Issued(UUID resetId, UUID userId, String status, String resetUrl, Instant expiresAt, String email) {}
    record Progress(UUID resetId, UUID userId, String status, Instant expiresAt, Instant completedAt) {}
    record Preview(String companyName, String email, Instant expiresAt) {}

    private List<Row> find(String column, String value) {
        return db.query("SELECT * FROM portal_accounts.password_resets WHERE " + column + "=?",
            (rs,n) -> new Row(rs.getString("reference_id"), rs.getObject("reset_id",UUID.class),
                rs.getObject("user_id",UUID.class), rs.getString("fingerprint"), rs.getString("email"),
                rs.getString("token_encrypted"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), value);
    }

    private Identity identity(String registration) {
        var rows = db.query("""
            SELECT u.id,u.email,u.active,u.password_change_required,u.password_hash,
                   m.organization_id,m.company_name FROM portal_accounts.registrations r
            JOIN portal_accounts.users u ON u.id=r.user_id
            JOIN portal_accounts.memberships m ON m.user_id=u.id WHERE r.reference_id=?
            """, (rs,n) -> new Identity(rs.getObject("id",UUID.class),rs.getString("organization_id"),
                rs.getString("email"),rs.getBoolean("active"),rs.getBoolean("password_change_required"),
                rs.getString("company_name"),rs.getString("password_hash")),registration);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found.");
        return rows.getFirst();
    }

    private static void available(Row row) {
        if (row.completedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This reset link has already been used. Sign in with your new password.");
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This reset link has expired. Contact your administrator for a new approved request.");
        }
    }

    @Transactional
    public Issued issue(PasswordResetApi.Issuance input) {
        db.queryForObject("SELECT pg_advisory_xact_lock(7612092)", Object.class);
        String email = Accounts.normalize(input.email());
        String fingerprint;
        try {
            fingerprint = Accounts.hash(json.writeValueAsString(
                List.of(input.registrationReference(),input.organizationId(),email)));
        } catch (Exception error) {
            throw new IllegalStateException("Could not identify reset request", error);
        }
        var previous = find("reference_id",input.referenceId());
        if (!previous.isEmpty() && !previous.getFirst().fingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset reference has different identity fields.");
        }
        var identity = identity(input.registrationReference());
        if (!identity.email().equals(email) || !identity.organizationId().equals(input.organizationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset identity does not match the registration.");
        }
        if (!identity.active() || identity.passwordChangeRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active account with a completed password is required.");
        }
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!row.userId().equals(identity.userId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset identity has changed.");
            }
            available(row);
            return issued(row);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var row = new Row(input.referenceId(),UUID.randomUUID(),identity.userId(),fingerprint,email,
            accounts.encrypt(token),Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS),null);
        db.update("""
            INSERT INTO portal_accounts.password_resets(
              reference_id,reset_id,user_id,registration_reference,fingerprint,email,token_hash,token_encrypted,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,row.referenceId(),row.resetId(),row.userId(),input.registrationReference(),row.fingerprint(),
            row.email(),Accounts.hash(token),row.encrypted(),Timestamp.from(row.expiresAt()));
        return issued(row);
    }

    private Issued issued(Row row) {
        return new Issued(row.resetId(),row.userId(),"PENDING",
            publicUrl+"/reset-password#token="+accounts.decrypt(row.encrypted()),row.expiresAt(),row.email());
    }

    Progress progress(String reference) {
        var rows = find("reference_id",reference);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reset request not found.");
        var row = rows.getFirst();
        return new Progress(row.resetId(),row.userId(),row.completedAt()!=null ? "COMPLETED"
            : row.expiresAt().isAfter(Instant.now()) ? "PENDING" : "EXPIRED",row.expiresAt(),row.completedAt());
    }

    Preview inspect(String token) {
        var row = token(token);
        available(row);
        String company = db.queryForObject(
            "SELECT company_name FROM portal_accounts.memberships WHERE user_id=?",String.class,row.userId());
        return new Preview(company,row.email(),row.expiresAt());
    }

    private Row token(String token) {
        var rows = find("token_hash",Accounts.hash(token));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reset link is unavailable.");
        return rows.getFirst();
    }

    @Transactional
    public void complete(PasswordResetApi.Completion input) {
        accounts.validateReplacementPassword(input.password());
        db.queryForObject("SELECT pg_advisory_xact_lock(7612092)", Object.class);
        var row = token(input.token());
        available(row);
        var users = db.query("""
            SELECT password_hash FROM portal_accounts.users
            WHERE id=? AND email=? AND active=TRUE AND password_change_required=FALSE
            """,(rs,n)->rs.getString(1),row.userId(),row.email());
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account is unavailable for password reset.");
        if (passwords.matches(input.password(),users.getFirst())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a password different from your current password.");
        }
        db.update("UPDATE portal_accounts.users SET password_hash=? WHERE id=?",
            passwords.encode(input.password()),row.userId());
        db.update("UPDATE portal_accounts.password_resets SET completed_at=now() WHERE reference_id=?",row.referenceId());
        db.update("""
            UPDATE portal_accounts.password_resets SET expires_at=LEAST(expires_at,now())
            WHERE user_id=? AND reference_id<>? AND completed_at IS NULL
            """,row.userId(),row.referenceId());
    }
}
