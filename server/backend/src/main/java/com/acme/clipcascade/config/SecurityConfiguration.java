package com.acme.clipcascade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import com.acme.clipcascade.service.FacadeUserService;
import com.acme.clipcascade.service.LoginAttemptService;
import com.acme.clipcascade.utils.IpAddressResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	private final UserDetailsService userDetailsService;
	private final BCryptPasswordEncoder bCryptPasswordEncoder;
	private final FacadeUserService facadeUserService;
	private final LoginAttemptService loginAttemptService;
	private final IpAddressResolver ipAddressResolver;
	private final AppProperties appProperties;

	SecurityConfiguration(
			UserDetailsService userDetailsService,
			BCryptPasswordEncoder bCryptPasswordEncoder,
			FacadeUserService facadeUserService,
			LoginAttemptService loginAttemptService,
			IpAddressResolver ipAddressResolver,
			AppProperties appProperties) {

		this.userDetailsService = userDetailsService;
		this.bCryptPasswordEncoder = bCryptPasswordEncoder;
		this.facadeUserService = facadeUserService;
		this.loginAttemptService = loginAttemptService;
		this.ipAddressResolver = ipAddressResolver;
		this.appProperties = appProperties;
	}

	// SessionRegistry bean to store session information
	@Bean
	public SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

	// Ensures the SessionRegistry is notified of session lifecycle events
	@Bean
	public HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		LoginAttemptFilter loginAttemptFilter = new LoginAttemptFilter(loginAttemptService, ipAddressResolver);
		LoginAttemptFailureHandler failureHandler = new LoginAttemptFailureHandler(loginAttemptService, ipAddressResolver);

		return http
				.authorizeHttpRequests((authorize) -> authorize
						.requestMatchers(
								"/login",
								"/logout",
								"/signup",
								"/captcha",
								"/help",
								"/donate",
								"/health",
								"/ping",
								"/assets/**")
						.permitAll()
						.requestMatchers("/api/ecdh/**").authenticated()  // ECDH post-login only
						.anyRequest().authenticated())
				.formLogin(form -> form
						.loginPage("/login")
						.failureHandler(failureHandler)
						.successHandler(
								new CustomAuthenticationSuccessHandler(facadeUserService)))
				.addFilterBefore(loginAttemptFilter, UsernamePasswordAuthenticationFilter.class)
				.logout(logout -> logout
						.logoutUrl("/logout")
						.logoutSuccessUrl("/login?logout"))
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp
								.policyDirectives(
										"default-src 'self'; " +
										"script-src 'self' 'unsafe-inline'; " +
										"style-src 'self' 'unsafe-inline'; " +
										"img-src 'self' data:; " +
										"frame-ancestors 'none'"
								)
						)
				)
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.ALWAYS)
						.maximumSessions(appProperties.getMaxSessions())
						.sessionRegistry(sessionRegistry())
						.expiredSessionStrategy(new CustomExpiredSession()))
				.build();
	}

	@Bean
	public AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setPasswordEncoder(bCryptPasswordEncoder);
		provider.setUserDetailsService(userDetailsService);
		return provider;
	}
}
