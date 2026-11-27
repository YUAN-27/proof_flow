package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
class StaffSecurityConfiguration {
    static final String PATH = "/api/v1/staff";

    @Bean
    @ConditionalOnProperty(name = "proofflow.staff.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain legacyCaseSecurity(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "proofflow.staff.enabled", havingValue = "true")
    SecurityFilterChain staffSecurity(HttpSecurity http, UserDetailsService staffUserDetailsService) throws Exception {
        var provider = new DaoAuthenticationProvider(staffUserDetailsService);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        return http.authenticationManager(new ProviderManager(provider))
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(CaseController.PATH, CaseController.PATH + "/**", PATH + "/me").hasRole("STAFF")
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.setStatus(401)))
            .formLogin(login -> login.loginPage(PATH + "/login").loginProcessingUrl(PATH + "/login")
                .successHandler((request, response, auth) -> response.setStatus(204))
                .failureHandler((request, response, error) -> response.setStatus(401)))
            .logout(logout -> logout.logoutUrl(PATH + "/logout")
                .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "proofflow.staff.enabled", havingValue = "true")
    UserDetailsService staffUserDetailsService(@Qualifier("caseMySqlDataSource") DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT 1 FROM staff_accounts LIMIT 0");
        }
        return username -> {
            if (!username.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new UsernameNotFoundException("Unknown staff account");
            }
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                    SELECT username, password_hash, enabled, tenant_id, actor_id
                    FROM staff_accounts WHERE username = ?
                    """)) {
                statement.setString(1, username);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return new StaffAccount(rows.getString("username"), rows.getString("password_hash"),
                            rows.getBoolean("enabled"), new ActorContext(rows.getString("tenant_id"), rows.getString("actor_id")));
                    }
                }
            } catch (SQLException error) {
                throw new InternalAuthenticationServiceException("Staff account lookup failed", error);
            }
            throw new UsernameNotFoundException("Unknown staff account");
        };
    }
}
