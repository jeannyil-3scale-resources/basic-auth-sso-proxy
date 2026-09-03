# basic-auth-sso-proxy

This project leverages [**Red Hat build of Quarkus 3.33.x**](https://docs.redhat.com/en/documentation/red_hat_build_of_quarkus/3.33), the Supersonic Subatomic Java Framework. More specifically, the project is implemented using [**Red Hat build of Apache Camel v4.18.x for Quarkus**](https://docs.redhat.com/en/documentation/red_hat_build_of_apache_camel/4.18#Red%20Hat%20build%20of%20Apache%20Camel%20for%20Quarkus).

_Basic Auth SSO Proxy_ is a camel proxy service that can be leveraged to configure the [_Red Hat 3scale APIcast Camel Service policy_](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management/2.16/html/administering_the_api_gateway/apicast-policies#camel-service_standard-policies). 

_Basic Auth SSO Proxy_ service extracts the username and password from the incoming HTTP request's Basic Authentication header. It uses these credentials to obtain an OIDC access token from the _Red Hat build of Keycloak_ server via the *Resource Owner Password Credentials Grant* (commonly known as the *password grant*). The acquired OIDC access token is then inserted into the *Authorization* HTTP header as a Bearer token before forwarding the request to the upstream backend service.

Here is the overall flow sequence diagram:

![Flow Sequence Diagram](./images/sequence-diagram.png)

## Prerequisites

- Apache Maven 3.9.9
- JDK 21 installed with `JAVA_HOME` configured appropriately
- A running [_Red Hat build of Keycloak_](https://access.redhat.com/documentation/en-us/red_hat_build_of_keycloak) instance with a set of test users (local DB or federated). The following must be configured:
    1. A confidential client with the following characteristics:
        - Client ID: `basic-auth-sso-proxy`
        - Client Protocol: `openid-connect`
        - Client authentication: `on`
        - Authentication flow: `Direct access grants (password grant)`
    2. Replace the `client secret` in:
        - `quarkus.oidc-client.credentials.secret` property in the [`application.yml`](./src/main/resources/application.yml) file
        - `quarkus.oidc-client.credentials.secret` property of the `basic-auth-sso-proxy-secret` in the [`openshift.yml`](./src/main/kubernetes/openshift.yml) file
    3. Replace the `OIDC authorization server URL` in:
        - `quarkus.oidc-client.auth-server-url`  property in the [`application.yml`](./src/main/resources/application.yml) file
        - `quarkus.oidc-client.auth-server-url` property of the `basic-auth-sso-proxy-secret` in the [`openshift.yml`](./src/main/kubernetes/openshift.yml) file
- A running [_Red Hat OpenShift_](https://access.redhat.com/documentation/en-us/openshift_container_platform) cluster
- A running [_Red Hat 3scale API Management_](https://access.redhat.com/documentation/en-us/red_hat_3scale_api_management) platform


## Generate a Java Keystore

```shell
keytool -genkey -keypass P@ssw0rd -storepass P@ssw0rd -alias basic-auth-sso-proxy -keyalg RSA \
-dname "CN=basic-auth-sso-proxy" \
-validity 3600 -keystore ./tls-keys/keystore.p12 -v \
-ext san=DNS:basic-auth-sso-proxy.svc,DNS:basic-auth-sso-proxy.svc.cluster.local,DNS:basic-auth-sso-proxy.camel-quarkus.svc,DNS:basic-auth-sso-proxy.camel-quarkus.svc.cluster.local,DNS:basic-auth-sso-proxy.ceq-services-jvm.svc,DNS:basic-auth-sso-proxy.ceq-services-jvm.svc.cluster.local,DNS:basic-auth-sso-proxy.ceq-services-native.svc,DNS:basic-auth-sso-proxy.ceq-services-native.svc.cluster.local
```

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell
./mvnw clean compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev-ui.

## Packaging and running the application locally

1. Execute the following command line:
    ```shell
    ./mvnw clean package
    ```

    It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
    Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.
    
    The application is now runnable using:
    ```
    java -Dquarkus.kubernetes-config.enabled=false -jar target/quarkus-app/quarkus-run.jar
    ```
    
    If you want to build an _über-jar_, execute the following command:
    ```
    ./mvnw clean package -Dquarkus.package.type=uber-jar
    ```
    
    The application, packaged as an _über-jar_, is now runnable using:
    ```
    java -Dquarkus.kubernetes-config.enabled=false -jar target/*-runner.jar
    ```

2. **OPTIONAL:** Creating a native executable

    You can create a native executable using the following command:

    ```shell
    ./mvnw clean package -Pnative -Dquarkus.native.native-image-xmx=7g
    ```

    >**NOTE** : The project is configured to use a container runtime for native builds. See `quarkus.native.container-build=true` in the [`application.yml`](./src/main/resources/application.yml). Also, adjust the `quarkus.native.native-image-xmx` value according to your container runtime available memory resources.

    You can then execute your native executable with: `./target/basic-auth-sso-proxy-1.0.0-runner`

    If you want to learn more about building native executables, please consult https://quarkus.io/guides/building-native-image.

    >**NOTE** : If your are on Apple Silicon and built the native image inside a Linux container (`-Dquarkus.native.container-build=true`), the result is a Linux ELF binary. macOS can’t execute Linux binaries, so launching it on macOS yields “`exec format error`”. Follow the steps below to run your Linux native binary.

    1. Build the container image of your Linux native binary:
        ```shell
        podman build -f src/main/docker/Dockerfile.native -t basic-auth-sso-proxy .
        ```
    2. Run the container:
        ```shell
        podman run --rm --name basic-auth-sso-proxy \
        -p 9443:9443,9876:9876 \
        -e QUARKUS_KUBERNETES-CONFIG_ENABLED=false \
        -e QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://host.containers.internal:4317 \
        -e BASIC-AUTH-SSO-PROXY.KEYSTORE.MOUNT-PATH=/mnt \
        -v ./tls-keys/keystore.p12:/mnt/keystore.p12:ro \
        basic-auth-sso-proxy
        ```

3. Running Jaeger locally

    [**Jaeger**](https://www.jaegertracing.io/), a distributed tracing system for observability ([_open tracing_](https://opentracing.io/)).  A simple way of starting a Jaeger tracing server is with `docker` or `podman`:
    1. Start the Jaeger tracing server:
        ```shell
        podman run --rm -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 -e COLLECTOR_OTLP_ENABLED=true \
        -p 6831:6831/udp -p 6832:6832/udp \
        -p 5778:5778 -p 16686:16686 -p 4317:4317 -p 4318:4318 -p 14250:14250  -p 14268:14268 -p 14269:14269 -p 9411:9411 \
        quay.io/jaegertracing/all-in-one:latest
        ```
    2. While the server is running, browse to http://localhost:16686 to view tracing events.

4. Test locally
    ```shell
    # Replace USERNAME and PASSWORD
    AUTH=$(printf 'USERNAME:PASSWORD' | base64)
    printf "GET https://echo-api.3scale.net/demo HTTP/1.1\r\nHost: echo-api.3scale.net\r\nAccept: */*\r\nAuthorization: Basic $AUTH\r\nProxy-Connection: keep-alive\r\nConnection: close\r\n\r\n" | \
    ncat --ssl localhost 9443
    ```

    **Tests examples**:

    - _**With valid credentials**_ (authenticated by the remote Red Hat build of Keycloak server):
        ```shell
        AUTH=$(printf 'JiJi:CorrectPwd' | base64)
        printf "GET https://echo-api.3scale.net/demo HTTP/1.1\r\nHost: echo-api.3scale.net\r\nAccept: */*\r\nAuthorization: Basic $AUTH\r\nProxy-Connection: keep-alive\r\nConnection: close\r\n\r\n" | \
        ncat --ssl localhost 9443
        ```
        ```shell
        HTTP/1.1 200 OK
        content-length: 2037
        server: envoy
        vary: Origin
        x-3scale-echo-api: echo-api/1.0.3
        x-content-type-options: nosniff
        x-envoy-upstream-service-time: 1
        content-type: application/json
        connection: close

        {
            "method": "GET",
            "path": "/demo",
            "args": "",
            "body": "",
            "headers": {
                "HTTP_VERSION": "HTTP/1.1",
                "HTTP_HOST": "echo-api.3scale.net",
                "HTTP_ACCEPT": "*/*,*/*",
                "HTTP_AUTHORIZATION": "Basic SmlKaTpQIXNzdzByZA==,Bearer eyJhbGciOiJSUzI1NiIsInR5cCI...<truncated>...CCRxdnNqeNw",
                "CONTENT_LENGTH": "0",
                "HTTP_TRACEPARENT": "00-640db7e65014bfd938e7636e069a10ca-a36e8e4b904f62bf-01",
                "HTTP_X_FORWARDED_FOR": "***.***.***.***",
                "HTTP_X_FORWARDED_PROTO": "https",
                "HTTP_X_ENVOY_EXTERNAL_ADDRESS": "***.***.***.***",
                "HTTP_X_REQUEST_ID": "028709d6-b54d-4f8d-aa1e-ccab4259c698",
                "HTTP_X_ENVOY_EXPECTED_RQ_TIMEOUT_MS": "15000"
            },
            "uuid": "db2d5c9e-a08b-4344-ae59-b483f507c434"
        }
        ```

    - _**With invalid credentials**_ (authenticated by the remote Red Hat build of Keycloak server):
        ```shell
        AUTH=$(printf 'JiJi:WrongPwd' | base64)
        printf "GET https://echo-api.3scale.net/demo HTTP/1.1\r\nHost: echo-api.3scale.net\r\nAccept: */*\r\nAuthorization: Basic $AUTH\r\nProxy-Connection: keep-alive\r\nConnection: close\r\n\r\n" | \
        ncat --ssl localhost 9443
        ```
        ```shell
        HTTP/1.1 401 Unauthorized
        content-length: 116
        Accept: */*
        Authorization: Basic SmlKaTpXcm9uZ1B3ZA==
        traceparent: 00-1f5541115030bb6676d21707c1d7a960-190ecef395718f41-01
        content-type: application/json
        connection: close

        {
        "code" : 401,
        "message" : "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}"
        }
        ```

    >**INFO**: The camel service listening on port `9443` proxies the HTTP request to the `echo-api.3scale.net` server. You should see similar log lines as the following in the camel service proxy logs:
    ```log
    2025-12-03 14:53:07,869 INFO  [or.je.ro.BasicAuthSSOProxyRoute] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup)
      Incoming headers: {Accept=*/*, Authorization=Basic SmlKaTpQIXNzdzByZA==, CamelHttpHost=echo-api.3scale.net,
        CamelHttpMethod=GET, CamelHttpPath=/demo, CamelHttpScheme=https, ...}
    2025-12-03 14:53:08,460 DEBUG [io.qu.oi.cl.ru.OidcClientImpl] (vert.x-eventloop-thread-6)
      Default OidcClient has acquired the tokens
    2025-12-03 14:53:08,464 INFO  [or.je.ro.BasicAuthSSOProxyRoute] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup)
      Headers propagated to the backend: {Accept=*/*, Authorization=Bearer eyJhbGciOiJSUzI1NiI...<truncated>,
        CamelHttpHost=echo-api.3scale.net, CamelHttpMethod=GET, CamelHttpPath=/demo, CamelHttpScheme=https, ...}
    2025-12-03 14:53:08,496 INFO  [or.ap.ca.co.ne.ht.HttpClientInitializerFactory] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup)
      Created SslContext javax.net.ssl.SSLContext@2f109101
    ```

## Packaging and running the application on Red Hat OpenShift

### Prerequisites

- Access to a [Red Hat OpenShift](https://access.redhat.com/documentation/en-us/openshift_container_platform) cluster v4
- User has self-provisioner privilege or has access to a working OpenShift project
- **OPTIONAL**: [**Jaeger**](https://www.jaegertracing.io/), a distributed tracing system for observability ([_open tracing_](https://opentracing.io/)).

### Common setup steps

1. Login to the OpenShift cluster
    ```shell
    oc login ...
    ```
2. Create an OpenShift project or use your existing OpenShift project. For instance:
    - JVM mode:
        ```shell
        oc new-project ceq-services-jvm --display-name="Red Hat build of Apache Camel for Quarkus Apps - JVM Mode"
        ```
    - Native mode:
        ```shell
        oc new-project ceq-services-native --display-name="Red Hat build of Apache Camel for Quarkus Apps - Native Mode"
        ```
3. Create secret containing the keystore
    ```shell
    oc create secret generic basic-auth-sso-proxy-keystore-secret \
    --from-file=keystore.p12=./tls-keys/keystore.p12
    ```
4. Adjust the `quarkus.otel.exporter.otlp.endpoint` property of the `basic-auth-sso-proxy-secret` in the [`openshift.yml`](./src/main/kubernetes/openshift.yml) file according to your OpenShift environment and where you installed the [_Jaeger_](https://www.jaegertracing.io/) server.

### Deploy using Quarkus JVM mode

After completing the [common setup steps](#common-setup-steps), deploy to OpenShift using the _**S2I binary workflow**_:

```shell
./mvnw clean package -Dquarkus.openshift.deploy=true
```

### ALTERNATIVE - Deploy using Quarkus native mode

> :warning: **Prerequisites**
> - For native compilation, a Linux X86_64 operating system or an OCI (Open Container Initiative) compatible container runtime, such as Podman or Docker is required.

After completing the [common setup steps](#common-setup-steps), package and deploy to OpenShift using the _**S2I binary workflow**_:

```shell
./mvnw clean package -Pnative \
-Dquarkus.openshift.deploy=true
```

## How to configure the _APIcast Camel Service_ policy to use this service

###  Prerequisite

- An API Product configured in _Red Hat 3scale API Management_. For instance, the sample `Echo API` can be used.

###  Instructions

1. Add and configure the [_APIcast Camel Service_](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management/2.16/html/administering_the_api_gateway/apicast-policies#camel-service_standard-policies) policy on the API Product
    - Reference: https://docs.redhat.com/en/documentation/red_hat_3scale_api_management/2.16/html/administering_the_api_gateway/transform-with-policy-extension_3scale#configure-apicast-policy-extension-in-fuse_3scale

2. Add and configure the [_APIcast Anonymous Access_](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management/2.15/html/administering_the_api_gateway/apicast-policies#anonymous-access_standard-policies) policy on the API Product. This disables 3scale's native authentication, delegating authentication responsibilities to the Camel proxy service and the _Red Hat build of Keycloak_, which authenticate requests using the provided Basic Authentication credentials.

3. Beware of the following note:
    > **NOTE**: 
    You cannot use `curl` (or any other HTTP client) to test the Camel HTTP proxy directly because the proxy does not support HTTP tunneling using the `CONNECT` method. When using HTTP tunneling with `CONNECT`, the transport is end-to-end encrypted, which does not allow the Camel HTTP proxy to mediate the payload. 
    You may test this with 3scale, which implements this as if proxying via HTTP but establishes a new TLS session towards the Camel application. If you need to perform integration tests against the Camel application you need to use a custom HTTP client. 
    You can use something like: 
    `printf "GET https://<backend url> HTTP/1.1\r\nHost: <backend_host>\r\nAccept: */*\r\nAuthorization: Basic $AUTH\r\nProxy-Connection: keep-alive\r\nConnection: close\r\n\r\n" | ncat --ssl <camel_proxy_app_host> <camel_proxy_app_port>`

Below is a sample screenshot of the [_Camel Service_](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management/2.16/html/administering_the_api_gateway/apicast-policies#camel-service_standard-policies) policy configuration:

![APIcast Camel Service Policy](./images/CamelServicePolicy.png)

Below is a sample test where you can notice the `Authorization` HTTP header has been enriched with the retrieved OpenID Connect access token (`HTTP_AUTHORIZATION` header in the `Echo API` response):

```shell
# Replace <3SCALE_API_PRODUCT_URL> with your 3scale API Product URL
http -v -a JiJi https://<3SCALE_API_PRODUCT_URL>/v2/echo/demo
```
```shell
http: password for JiJi@<3SCALE_API_PRODUCT_URL>:: 
GET /v2/echo/demo HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Authorization: Basic SmlKaTpQIXNzdzByZA==
Connection: keep-alive
Host: <3SCALE_API_PRODUCT_URL>
User-Agent: HTTPie/3.2.4

HTTP/1.1 200 OK
content-type: application/json
date: Tue, 10 Dec 2024 18:30:59 GMT
server: envoy
set-cookie: c1f630e14b6ce8dc15329e7ab6d99cc9=cfe3ce014c81789d4aa6e0944106634b; path=/; HttpOnly; Secure; SameSite=None
transfer-encoding: chunked
vary: Origin
x-3scale-echo-api: echo-api/1.0.3
x-content-type-options: nosniff
x-envoy-upstream-service-time: 0

{
    "args": "",
    "body": "",
    "headers": {
        "CONTENT_LENGTH": "0",
        "HTTP_ACCEPT": "*/*,*/*",
        "HTTP_ACCEPT_ENCODING": "gzip, deflate,gzip, deflate",
        "HTTP_AUTHORIZATION": "Basic SmlKaTpQIXNzdzByZA==,Bearer eyJhbGciOiJSUzI1NiIsInR5cCI...<truncated>...kXlXSiw",
        "HTTP_FORWARDED": "for=***.***.***.***;host=<3SCALE_API_PRODUCT_URL>;proto=https, ...",
        "HTTP_HOST": "echo-api.3scale.net",
        "HTTP_TRACEPARENT": "00-5bc35ff31b02eb8261aa16f803a5029f-3f54bc2887e63fc6-01",
        "HTTP_USER_AGENT": "HTTPie/3.2.4,HTTPie/3.2.4",
        "HTTP_VERSION": "HTTP/1.1",
        "HTTP_X_ENVOY_EXPECTED_RQ_TIMEOUT_MS": "15000",
        "HTTP_X_ENVOY_EXTERNAL_ADDRESS": "***.***.***.***",
        "HTTP_X_FORWARDED_FOR": "***.***.***.***,***.***.***.***,***.***.***.***",
        "HTTP_X_FORWARDED_HOST": "<3SCALE_API_PRODUCT_URL>,<3SCALE_API_PRODUCT_URL>",
        "HTTP_X_FORWARDED_PORT": "443, 443",
        "HTTP_X_FORWARDED_PROTO": "https",
        "HTTP_X_REQUEST_ID": "816483e9-9321-4588-b1d6-d1bd50971a86"
    },
    "method": "GET",
    "path": "/demo",
    "uuid": "2c6c90a9-d398-43aa-8d63-bdcf3d1ebaea"
}
```