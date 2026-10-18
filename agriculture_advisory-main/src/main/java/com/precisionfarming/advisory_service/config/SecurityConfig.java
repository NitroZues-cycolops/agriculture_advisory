package com.precisionfarming.advisory_service.config;

import com.precisionfarming.advisory_service.repository.LabDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.util.Locale;

@Configuration
@EnableWebSecurity
public class SecurityConfig implements WebMvcConfigurer {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/signup", "/login", "/register", "/register/lab",
                                 "/verify", "/verify/**",
                                 "/css/**", "/images/**").permitAll()
                .requestMatchers("/debug/**").hasRole("OWNER")
                .requestMatchers("/owner", "/owner/**").hasRole("OWNER")
                .requestMatchers("/lab/dashboard", "/lab/dashboard/**").hasRole("LAB")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                // PART 1: an account whose EMAIL is not verified (or a lab that
                // the owner has not approved) is DISABLED, and Spring Security
                // throws DisabledException. That is not the same as a wrong
                // password, so the visitor gets its own message.
                .failureHandler((request, response, exception) -> {
                    String target = exception instanceof DisabledException
                            ? "/login?disabled" : "/login?error";
                    response.sendRedirect(request.getContextPath() + target);
                })
                .defaultSuccessUrl("/")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );
        
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository, LabDao labDao) {
        return username -> userRepository.findByUsername(username)
            .map(u -> User.withUsername(u.getUsername())
                .password(u.getPassword())
                .roles(u.getRole())
                .disabled(loginBlocked(u, labDao))
                .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * PART 1 login rule - the ONE place that decides whether an account may log
     * in. A self-registered FARMER or LAB may log in ONLY once the EMAIL on the
     * account is verified; a LAB additionally needs the owner's approval
     * (labs.status = 'APPROVED', the STEP 7 rule). The OWNER is never blocked.
     * A blocked account is "disabled", so Spring Security throws
     * DisabledException and the visitor is sent to /login?disabled.
     */
    private static boolean loginBlocked(com.precisionfarming.advisory_service.model.User u, LabDao labDao) {
        String role = u.getRole();
        if ("OWNER".equals(role)) {
            return false;
        }
        if (!u.isEmailVerified()) {
            return true;
        }
        if ("LAB".equals(role)) {
            return !labDao.findByUserId(u.getId())
                    .map(l -> "APPROVED".equals(l.getStatus()))
                    .orElse(false);
        }
        return false;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // --- I18N ---
    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver slr = new SessionLocaleResolver();
        slr.setDefaultLocale(Locale.ENGLISH);
        return slr;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        lci.setParamName("lang");
        return lci;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
