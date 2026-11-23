package portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class Accounts {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    private final SecretKeySpec encryptionKey;
    private final String publicUrl;
    private final String registrationToken;
    private final String demoUsername;
    private final String sandboxUsername;
    private final String demoOrganization;
    private final String initialPassword;
    private final SecureRandom random = new SecureRandom();

    Accounts(JdbcTemplate db, ObjectMapper json, PasswordEncoder passwords,
            @Value("${portal.invitation-key}") String invitationKey,
            @Value("${portal.public-url}") String publicUrl,
            @Value("${portal.registration-token}") String registrationToken,
            @Value("${portal.username}") String demoUsername,
            @Value("${portal.sandbox-username}") String sandboxUsername,
            @Value("${portal.organization}") String demoOrganization,
            @Value("${PORTAL_INITIAL_PASSWORD:Portal123!}") String initialPassword) {
        this.db = db;
        this.json = json;
        this.passwords = passwords;
        byte[] key = Base64.getDecoder().decode(invitationKey);
        if (key.length != 32 || registrationToken.length() < 16) {
            throw new IllegalArgumentException("Configure a 32-byte invitation key and registration service token");
        }
        this.encryptionKey = new SecretKeySpec(key, "AES");
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.registrationToken = registrationToken;
        this.demoUsername = normalize(demoUsername);
        this.sandboxUsername = normalize(sandboxUsername);
        this.demoOrganization = demoOrganization;
        this.initialPassword = initialPassword;
    }

    @PostConstruct
    void schema() {
        db.execute("""
            CREATE SCHEMA IF NOT EXISTS portal_accounts;
            CREATE TABLE IF NOT EXISTS portal_accounts.users (
              id UUID PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
              password_hash TEXT, active BOOLEAN NOT NULL DEFAULT FALSE
            );
            CREATE TABLE IF NOT EXISTS portal_accounts.memberships (
              user_id UUID PRIMARY KEY REFERENCES portal_accounts.users(id), organization_id TEXT NOT NULL,
              company_name TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'PROJECT_MANAGER',
              production_access BOOLEAN NOT NULL DEFAULT FALSE
            );
            CREATE TABLE IF NOT EXISTS portal_accounts.registrations (
              reference_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
              user_id UUID NOT NULL REFERENCES portal_accounts.users(id),
              token_hash TEXT UNIQUE NOT NULL, token_encrypted TEXT NOT NULL,
              expires_at TIMESTAMPTZ NOT NULL, accepted_at TIMESTAMPTZ
            );
            ALTER TABLE portal_accounts.users ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE portal_accounts.users ADD COLUMN IF NOT EXISTS registration_source TEXT NOT NULL DEFAULT 'BACKSTAGE'
              CHECK (registration_source IN ('BACKSTAGE','SELF_ONBOARDING'));
            """);
        db.update("""
            UPDATE portal_accounts.memberships m SET production_access=TRUE
            FROM portal_accounts.users u
            WHERE u.id=m.user_id AND u.registration_source='BACKSTAGE' AND m.production_access=FALSE
            """);
        db.update("""
            UPDATE portal_accounts.users SET password_hash=?,password_change_required=TRUE
            WHERE active=FALSE AND password_hash IS NULL
            """, passwords.encode(initialPassword));
    }

    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    void serviceAccess(String authorization) {
        if (!MessageDigest.isEqual(hash(authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8),
                hash("Bearer " + registrationToken).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Registration service credentials required.");
        }
    }

    static String hash(String input) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    String encrypt(String input) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception error) {
            throw new IllegalStateException("Could not protect invitation", error);
        }
    }

    String decrypt(String input) {
        try {
            byte[] data = Base64.getDecoder().decode(input);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Could not read invitation", error);
        }
    }

    private record Registration(String reference, String fingerprint, UUID userId, String encrypted,
                                Instant expiresAt, Instant acceptedAt) {}
    record Registered(UUID userId, String status, String invitationUrl, Instant expiresAt) {}
    record Progress(UUID userId, String status, Instant expiresAt, Instant acceptedAt) {}
    record Invitation(String name, String email, String companyName, Instant expiresAt) {}
    record Membership(String organizationId, String companyName, String role, boolean productionAccess,
                      String registrationSource) {
        String defaultEnvironment() {
            return productionAccess && !registrationSource.equals("SELF_ONBOARDING") ? "PRODUCTION" : "SANDBOX";
        }
    }

    private List<Registration> registrations(String column, String value) {
        return db.query("SELECT * FROM portal_accounts.registrations WHERE " + column + " = ?",
            (rs, index) -> new Registration(rs.getString("reference_id"), rs.getString("fingerprint"),
                rs.getObject("user_id", UUID.class), rs.getString("token_encrypted"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant()), value);
    }

    @Transactional
    public Registered register(Onboarding.Registration input) {
        // Serialize registrations and acceptance to make duplicate identities and token use atomic.
        db.queryForObject("SELECT pg_advisory_xact_lock(7612092)", Object.class);
        String email = normalize(input.email());
        if (email.equals(demoUsername) || email.equals(sandboxUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is reserved for a development account.");
        }
        String fingerprint;
        try {
            fingerprint = hash(json.writeValueAsString(List.of(input.organizationId().strip(),
                input.companyName().strip(), email, input.name().strip())));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        var previous = registrations("reference_id", input.referenceId());
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration reference has different identity fields.");
            }
            usable(row);
            return result(row);
        }
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM portal_accounts.users WHERE email=?)", Boolean.class, email))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This email already has a registration. Use the original reference or contact support.");
        }
        UUID userId = UUID.randomUUID();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("INSERT INTO portal_accounts.users(id,email,name,password_hash,password_change_required,registration_source) VALUES (?,?,?,?,TRUE,'BACKSTAGE')",
            userId, email, input.name().strip(), passwords.encode(initialPassword));
        db.update("INSERT INTO portal_accounts.memberships(user_id,organization_id,company_name,production_access) VALUES (?,?,?,TRUE)",
            userId, input.organizationId().strip(), input.companyName().strip());
        String encrypted = encrypt(token);
        db.update("""
            INSERT INTO portal_accounts.registrations(reference_id,fingerprint,user_id,token_hash,token_encrypted,expires_at)
            VALUES (?,?,?,?,?,?)
            """, input.referenceId(), fingerprint, userId, hash(token), encrypted, Timestamp.from(expiry));
        return result(new Registration(input.referenceId(), fingerprint, userId, encrypted, expiry, null));
    }

    private Registered result(Registration row) {
        return new Registered(row.userId(), "INVITED", publicUrl + "/onboarding#token=" + decrypt(row.encrypted()), row.expiresAt());
    }

    private static void usable(Registration row) {
        if (row.acceptedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation has already been accepted.");
        if (!row.expiresAt().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "Invitation has expired. Contact your administrator.");
    }

    Progress progress(String reference) {
        var rows = registrations("reference_id", reference);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found.");
        var row = rows.getFirst();
        return new Progress(row.userId(), row.acceptedAt() != null ? "ACTIVE"
            : row.expiresAt().isAfter(Instant.now()) ? "INVITED" : "EXPIRED", row.expiresAt(), row.acceptedAt());
    }

    Invitation inspect(String token) {
        var rows = registrations("token_hash", hash(token));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation is unavailable.");
        var row = rows.getFirst();
        usable(row);
        return db.queryForObject("""
            SELECT u.name,u.email,m.company_name FROM portal_accounts.users u
            JOIN portal_accounts.memberships m ON m.user_id=u.id WHERE u.id=?
            """, (rs, index) -> new Invitation(rs.getString("name"), rs.getString("email"),
                rs.getString("company_name"), row.expiresAt()), row.userId());
    }

    @Transactional
    public void accept(Onboarding.Acceptance input) {
        db.queryForObject("SELECT pg_advisory_xact_lock(7612092)", Object.class);
        var rows = registrations("token_hash", hash(input.token()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation is unavailable.");
        var row = rows.getFirst();
        String email = db.queryForObject("SELECT email FROM portal_accounts.users WHERE id=?", String.class, row.userId());
        if (!normalize(input.email()).equals(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the invited email address.");
        }
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes.");
        }
        if (row.acceptedAt() != null) {
            String encoded = db.queryForObject("SELECT password_hash FROM portal_accounts.users WHERE id=?", String.class, row.userId());
            // Replay a lost activation response only for the original password. Never reset an active account.
            if (passwords.matches(input.password(), encoded)) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation has already been accepted.");
        }
        usable(row);
        db.update("UPDATE portal_accounts.users SET password_hash=?,active=TRUE,password_change_required=FALSE WHERE id=?", passwords.encode(input.password()), row.userId());
        db.update("UPDATE portal_accounts.registrations SET accepted_at=now() WHERE reference_id=?", row.reference());
    }

    UserDetails invitedUser(String username) {
        var rows = db.query("""
            SELECT u.email,u.password_hash,u.password_change_required,m.role,m.production_access FROM portal_accounts.users u
            JOIN portal_accounts.memberships m ON m.user_id=u.id WHERE u.email=? AND (u.active=TRUE OR u.password_change_required=TRUE)
            """, (rs, index) -> {
                var user = User.withUsername(rs.getString("email")).password(rs.getString("password_hash"));
                if (rs.getBoolean("password_change_required")) return user.roles("PASSWORD_CHANGE_REQUIRED").build();
                return user.roles(rs.getBoolean("production_access")
                    ? new String[] {rs.getString("role"), "PRODUCTION"} : new String[] {rs.getString("role")}).build();
            }, normalize(username));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    boolean passwordChangeRequired(Authentication actor) {
        return actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PASSWORD_CHANGE_REQUIRED"));
    }

    void validateReplacementPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes.");
        }
        if (password.equals(initialPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a password different from the initial password.");
        }
    }

    @Transactional
    public void changeInitialPassword(Authentication actor, String password) {
        validateReplacementPassword(password);
        db.queryForObject("SELECT pg_advisory_xact_lock(7612092)", Object.class);
        var ids = db.query("SELECT id FROM portal_accounts.users WHERE email=? AND password_change_required=TRUE",
            (rs, i) -> rs.getObject(1, UUID.class), normalize(actor.getName()));
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Password has already been changed.");
        db.update("UPDATE portal_accounts.users SET password_hash=?,active=TRUE,password_change_required=FALSE WHERE id=?",
            passwords.encode(password), ids.getFirst());
        db.update("UPDATE portal_accounts.registrations SET accepted_at=COALESCE(accepted_at,now()) WHERE user_id=?", ids.getFirst());
    }

    Membership membership(Authentication actor) {
        String email = normalize(actor.getName());
        if (email.equals(demoUsername) || email.equals(sandboxUsername)
                || actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_DEMO"))) {
            boolean production = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PRODUCTION"));
            return new Membership(demoOrganization, "Demo workspace", "PARTNER", production, "DEMO");
        }
        var rows = db.query("""
            SELECT organization_id,company_name,role,production_access,u.registration_source FROM portal_accounts.memberships m
            JOIN portal_accounts.users u ON u.id=m.user_id WHERE u.email=? AND u.active=TRUE
            """, (rs, index) -> new Membership(rs.getString("organization_id"), rs.getString("company_name"),
                rs.getString("role"), rs.getBoolean("production_access"), rs.getString("registration_source")), email);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active membership required.");
        return rows.getFirst();
    }
}
