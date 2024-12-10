package org.jeannyil.routes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.RouteDefinition;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BasicAuthSSOProxyRoute extends RouteBuilder {

    private static String logName = BasicAuthSSOProxyRoute.class.getName();

    @ConfigProperty(name = "basic-auth-sso-proxy.keystore.mount-path")
    String keystoreMountPath;

    @Override
    public void configure() throws Exception {

        // Catch unexpected exceptions
		onException(java.lang.Exception.class)
            .handled(false)
            .maximumRedeliveries(0)
            .log(LoggingLevel.ERROR, logName, ">>> ${routeId} - Caught exception: ${exception.stacktrace}")
        ;

        final RouteDefinition from;
        if (Files.exists(keystorePath())) {
            from = from("netty-http:proxy://0.0.0.0:{{basic-auth-sso-proxy.port.secure}}"
                        + "?ssl=true&keyStoreFile=#keystoreFile"
                        + "&passphrase={{basic-auth-sso-proxy.keystore.passphrase}}"
                        + "&trustStoreFile=#keystoreFile");
        } else {
            from = from("netty-http:proxy://0.0.0.0:{{basic-auth-sso-proxy.port.nonsecure}}");
        }

        from
            .routeId("basic-auth-sso-proxy-route")
            .log(LoggingLevel.INFO, logName, "Incoming headers: ${headers}")
            // Extract username and password from Basic Auth header
            .process("basicAuthProcessor")
            // Obtain OIDC Access Token using password grant and replace Authorization header with this OIDC access token as a Bearer token
            .process("oidcAccessTokenProcessor")
            .log(LoggingLevel.INFO, logName, "Headers propagated to the backend: ${headers}")
            // Call backend service
            .toD("netty-http:"
                + "${headers." + Exchange.HTTP_SCHEME + "}://"
                + "${headers." + Exchange.HTTP_HOST + "}:"
                + "${headers." + Exchange.HTTP_PORT + "}"
                + "${headers." + Exchange.HTTP_PATH + "}"
                + "?synchronous=true")
        ;
        
    }

    Path keystorePath() {
        return Path.of(keystoreMountPath, "keystore.p12");
    }

    @Named("keystoreFile")
    File getKeystoreFile() {
        return keystorePath().toFile();
    }
    
}
