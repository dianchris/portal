package portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PortalApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }

    @Bean
    PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(Accounts accounts, PasswordEncoder encoder,
            @Value("${portal.username}") String username,
            @Value("${portal.password}") String password,
            @Value("${portal.sandbox-username}") String sandboxUsername,
            @Value("${portal.sandbox-password}") String sandboxPassword,
            @Value("${PORTAL_DEMO_ACCOUNTS_ENABLED:false}") boolean demoEnabled) {
        if (password.isBlank()) throw new IllegalArgumentException("PORTAL_PASSWORD is required");
        var production = User.withUsername(username)
            .password(encoder.encode(password)).roles("PARTNER", "PRODUCTION").build();
        var demo = new InMemoryUserDetailsManager(production);
        if (!sandboxPassword.isBlank()) {
            if (username.equalsIgnoreCase(sandboxUsername) || password.equals(sandboxPassword)) {
                throw new IllegalArgumentException("Use distinct usernames and passwords for the demo accounts");
            }
            demo.createUser(User.withUsername(sandboxUsername)
                .password(encoder.encode(sandboxPassword)).roles("PARTNER").build());
        }
        if (demoEnabled) {
            for (var sample : DemoAccounts.ACCOUNTS) {
                if (demo.userExists(sample.username())) {
                    throw new IllegalArgumentException("Sample username conflicts with a configured account");
                }
                demo.createUser(User.withUsername(sample.username()).password(encoder.encode(sample.password()))
                    .roles(sample.productionAccess() ? new String[] {"PARTNER", "DEMO", "PRODUCTION"}
                        : new String[] {"PARTNER", "DEMO"}).build());
            }
        }
        return login -> {
            if (demo.userExists(login)) return demo.loadUserByUsername(login);
            var invited = accounts.invitedUser(login);
            if (invited == null) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Account unavailable");
            return invited;
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain onboardingSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/v1/registrations", "/internal/v1/registrations/**", "/api/onboarding/**", "/api/demo/accounts",
                "/internal/v1/password-resets", "/internal/v1/password-resets/**", "/api/password-resets/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            // The only write endpoint requires a custom header and accepts JSON.
            // Cross-origin preflight receives no CORS permission.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/account/**").authenticated()
                .anyRequest().access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                    authentication.get().isAuthenticated() && authentication.get().getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_PASSWORD_CHANGE_REQUIRED")))))
            .httpBasic(Customizer.withDefaults()).build();
    }
}
