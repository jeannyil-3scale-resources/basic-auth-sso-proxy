package org.jeannyil.processors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.core.Response;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.common.runtime.OidcConstants;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.http.HttpHeaders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Named("oidcAccessTokenProcessor")
@RegisterForReflection // Lets Quarkus register this class for reflection during the native build
public class OidcAccessTokenProcessor implements Processor {

    @Inject
    OidcClient oidcClient;

    // Cache for tokens per username-password combination
    private final ConcurrentHashMap<String, Tokens> tokenCache = new ConcurrentHashMap<>();

    @Override
    public void process(Exchange exchange) throws Exception {
        // Retrieve username and password from Exchange properties
        String username = exchange.getProperty("username", String.class);
        String password = exchange.getProperty("password", String.class);

        if (username == null || password == null) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, Response.Status.UNAUTHORIZED.getStatusCode());
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_TEXT, Response.Status.UNAUTHORIZED.getReasonPhrase());
            throw new IllegalArgumentException("Username or password are missing in Basic Auth credentials");
        }

        // Generate a cache key based on username and password
        String cacheKey = username + ":" + password;

        // Retrieve or refresh tokens for the given key
        Tokens tokens = tokenCache.get(cacheKey);
        if (tokens == null || tokens.isAccessTokenExpired()) {
            tokens = oidcClient.getTokens(
                                        Map.of(
                                            OidcConstants.PASSWORD_GRANT_USERNAME, username, 
                                            OidcConstants.PASSWORD_GRANT_PASSWORD, password)
                                ).await().indefinitely();
            tokenCache.put(cacheKey, tokens);
        }

        // Set the Authorization header with the cached or refreshed access token
        exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION.toString(), "Bearer " + tokens.getAccessToken());
    }
}