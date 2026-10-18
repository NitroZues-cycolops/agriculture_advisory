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
                // PART B: an account whose phone is not verified (or a lab that the
                // owner has not approved) is DISABLED, and Spring Security throws
                // DisabledException. That is not the same as a wrong password, so
                // the visitor gets its own message plus a "resend the code" form
                // instead of the generic credentials error.
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
                // A LAB account may log in only after the owner has APPROVED it.
                // Until then the account is disabled, so Spring Security rejects
                // the login (DisabledException) instead of letting a PENDING lab
                // into the dashboard.
                .disabled(labNotApproved(u, labDao) || phoneNotVerified(u))
                .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /** A lab stays locked out until the owner approves it (STEP 7 rule). */
    private static boolean labNotApproved(com.precisionfarming.advisory_service.model.User u, LabDao labDao) {
        return "LAB".equals(u.getRole())
                && !labDao.findByUserId(u.getId())
                        .map(l -> "APPROVED".equals(l.getStatus()))
                        .orElse(false);
    }

    /**
     * PART B: a self-registered FARMER or LAB may not log in until the phone on
     * the account is verified. The seeded demo accounts are marked verified in
     * data_new.sql, so they keep working. The owner account is not affected.
     */
    private static boolean phoneNotVerified(com.precisionfarming.advisory_service.model.User u) {
        return !u.isPhoneVerified()
                && ("FARMER".equals(u.getRole()) || "LAB".equals(u.getRole()));
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
