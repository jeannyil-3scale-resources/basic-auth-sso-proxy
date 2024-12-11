package org.jeannyil.processors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.ws.rs.core.Response;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Base64;

@ApplicationScoped
@Named("basicAuthProcessor")
@RegisterForReflection // Lets Quarkus register this class for reflection during the native build
public class BasicAuthProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        // Get the Authorization header
        String authHeader = exchange.getIn().getHeader("Authorization", String.class);
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, Response.Status.UNAUTHORIZED.getStatusCode());
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_TEXT, Response.Status.UNAUTHORIZED.getReasonPhrase());
            throw new IllegalArgumentException("Missing or invalid Authorization header for Basic Auth");
        }

        // Extract and decode the Base64 credentials
        String base64Credentials = authHeader.substring("Basic ".length());
        String credentials = new String(Base64.getDecoder().decode(base64Credentials));

        // Split the credentials into username and password
        String[] parts = credentials.split(":", 2);
        if (parts.length != 2) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, Response.Status.UNAUTHORIZED.getStatusCode());
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_TEXT, Response.Status.UNAUTHORIZED.getReasonPhrase());
            throw new IllegalArgumentException("Invalid Basic Auth credentials format");
        }

        String username = parts[0];
        String password = parts[1];

        // Set the username and password as Exchange properties
        exchange.setProperty("username", username);
        exchange.setProperty("password", password);
    }
}