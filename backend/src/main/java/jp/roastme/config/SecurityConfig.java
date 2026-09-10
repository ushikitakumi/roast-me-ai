package jp.roastme.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  UserDetailsService users(
    @Value("${app.backend-user}") String user,
    @Value("${app.backend-password}") String password
  ) {
    if (
      user.isBlank() || password.length() < 16
    ) throw new IllegalStateException(
      "Backend credentials require a username and a password of at least 16 characters"
    );
    return new InMemoryUserDetailsManager(
      User.withUsername(user)
        .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
        .roles("OWNER")
        .build()
    );
  }

  @Bean
  SecurityFilterChain filter(HttpSecurity http) throws Exception {
    // Only the authenticated BFF uses backend Basic credentials; browser CSRF is checked in the BFF.
    return http
      .csrf(c -> c.disable())
      .sessionManagement(s ->
        s.sessionCreationPolicy(
          org.springframework.security.config.http.SessionCreationPolicy.STATELESS
        )
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers("/actuator/health")
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .httpBasic(Customizer.withDefaults())
      .build();
  }
}
