/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.test.keyvault.group;

import com.microsoft.azure.spring.autoconfigure.aad.AADAuthenticationFilter;
import com.microsoft.azure.test.AppRunner;
import com.microsoft.azure.test.oauth.OAuthResponse;
import com.microsoft.azure.test.oauth.OAuthUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static com.microsoft.azure.test.oauth.OAuthUtils.AAD_CLIENT_ID;
import static com.microsoft.azure.test.oauth.OAuthUtils.AAD_CLIENT_SECRET;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.springframework.http.HttpHeaders.COOKIE;
import static org.springframework.http.HttpHeaders.SET_COOKIE;

@Slf4j
public class AADAuthenticationFilterIT {

    private RestTemplate restTemplate = new RestTemplate();

    @Test
    public void testAADAuthenticationFilter() {
        final OAuthResponse authResponse = OAuthUtils.executeOAuth2ROPCFlow();
        assertNotNull(authResponse);

        try (AppRunner app = new AppRunner(DumbApp.class)) {

            app.property("azure.activedirectory.client-id", System.getenv(AAD_CLIENT_ID));
            app.property("azure.activedirectory.client-secret", System.getenv(AAD_CLIENT_SECRET));
            app.property("azure.activedirectory.ActiveDirectoryGroups", "group1,group2");

            app.start();

            final ResponseEntity<String> response = restTemplate.exchange(app.root() + "home",
                    HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class, new HashMap<>());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("home", response.getBody());

            final HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", String.format("Bearer %s", authResponse.getIdToken()));
            HttpEntity entity = new HttpEntity(headers);

            final ResponseEntity<String> response2 = restTemplate.exchange(app.root() + "api/all",
                    HttpMethod.GET, entity, String.class, new HashMap<>());
            assertEquals(HttpStatus.OK, response2.getStatusCode());
            assertEquals("all", response2.getBody());

            final List<String> cookies = response2.getHeaders().getOrDefault(SET_COOKIE, new ArrayList<>());
            final Optional<String> sessionCookie = cookies.stream().filter(s -> s.startsWith("JSESSIONID=")).findAny();

            if (sessionCookie.isPresent()) {
                headers.add(COOKIE, sessionCookie.get());
                entity = new HttpEntity(headers);
            }

            final ResponseEntity<String> response3 = restTemplate.exchange(app.root() + "api/group1",
                    HttpMethod.GET, entity, String.class, new HashMap<>());
            assertEquals(HttpStatus.OK, response3.getStatusCode());
            assertEquals("group1", response3.getBody());

            try {
                restTemplate.exchange(app.root() + "api/group2",
                        HttpMethod.GET, entity, String.class, new HashMap<>());
            } catch (Exception e) {
                assertEquals(HttpClientErrorException.Forbidden.class, e.getClass());
            }

            app.close();
            log.info("--------------------->test over");
        }
    }

    @EnableGlobalMethodSecurity(securedEnabled = true, prePostEnabled = true)
    @SpringBootApplication
    @RestController
    public static class DumbApp extends WebSecurityConfigurerAdapter {

        @Autowired
        private AADAuthenticationFilter aadAuthFilter;

        @Override
        protected void configure(HttpSecurity http) throws Exception {

            http.authorizeRequests().antMatchers("/home").permitAll();
            http.authorizeRequests().antMatchers("/api/**").authenticated();

            http.logout().logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                    .logoutSuccessUrl("/").deleteCookies("JSESSIONID").invalidateHttpSession(true);

            http.authorizeRequests().anyRequest().permitAll();

            http.csrf().csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());

            http.addFilterBefore(aadAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        @GetMapping(value = "/api/all")
        public ResponseEntity<String> getAll() {
            return new ResponseEntity<>("all", HttpStatus.OK);
        }

        @PreAuthorize("hasRole('ROLE_group1')")
        @GetMapping(value = "/api/group1")
        public ResponseEntity<String> getRoleGroup1() {
            return new ResponseEntity<>("group1", HttpStatus.OK);
        }

        @PreAuthorize("hasRole('ROLE_group2')")
        @GetMapping(value = "/api/group2")
        public ResponseEntity<String> getRoleGroup2() {
            return new ResponseEntity<>("group2", HttpStatus.OK);
        }

        @GetMapping(value = "/home")
        public ResponseEntity<String> getHome() {
            return new ResponseEntity<>("home", HttpStatus.OK);
        }
    }

}
