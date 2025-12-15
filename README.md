# basic-auth-sso-proxy

This project leverages [**Red Hat build of Quarkus 3.27.x**](https://docs.redhat.com/en/documentation/red_hat_build_of_quarkus/3.27), the Supersonic Subatomic Java Framework. More specifically, the project is implemented using [**Red Hat build of Apache Camel v4.14.x for Quarkus**](https://docs.redhat.com/en/documentation/red_hat_build_of_apache_camel/4.14#Red%20Hat%20build%20of%20Apache%20Camel%20for%20Quarkus).

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
    ```shell
    java -Dquarkus.kubernetes-config.enabled=false -jar target/quarkus-app/quarkus-run.jar
    ```

    If you want to build an _über-jar_, execute the following command:
    ```shell
    ./mvnw clean package -Dquarkus.package.type=uber-jar
    ```

    The application, packaged as an _über-jar_, is now runnable using:
    ```shell
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
        ```
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
                "HTTP_AUTHORIZATION": "Basic SmlKaTpQIXNzdzByZA==,Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6bXRzSnd0WWxBd2JxOHplMlZNWDZDU0hOMG9OcW5scU0zbDBwX1dBWm1vIn0.eyJleHAiOjE3NjQ3NzAyODgsImlhdCI6MTc2NDc2OTk4OCwianRpIjoib25ydHJvOmVjYTBhNWViLTk2MDItNWQ2Mi1iZjNmLTJmODg0MjBhZWRkMiIsImlzcyI6Imh0dHBzOi8vc3NvLmFwcHMub2NwNC5qbnlpbGltYi5ldS9yZWFsbXMvZGVtbyIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJjNzU2NGVkYS1jYWQ0LTQ3MDItOWE5OS03MDk0YmZhNWE5MTciLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJiYXNpYy1hdXRoLXNzby1wcm94eSIsInNpZCI6IjA0ZjdmNGM2LTUyY2UtNzYwYy0xZDhmLTY4ZDFmZTgxOWU4MyIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiLyoiXSwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbIm9mZmxpbmVfYWNjZXNzIiwiZGVmYXVsdC1yb2xlcy1kZW1vIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJzY29wZSI6ImVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkppSmkgS2FyYW1lbGEiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJqaWppIiwiZ2l2ZW5fbmFtZSI6IkppSmkiLCJmYW1pbHlfbmFtZSI6IkthcmFtZWxhIiwiZW1haWwiOiJqbnlpbGltYitqaWppQHJlZGhhdC5jb20ifQ.K0lxUfwD0Jdl06htmlCVVnVm7-uXELUUOrPRzDdkREEB5fYxSh-Sm28J7qmF4aUXxu5fMz6-o58plTywwZtTkZwgqqRdOfv1LbhD02N-_Rpq4iQlJPGZEmfBn3_TAUlQEjyRhLZ_opkeeflUjZSiaA0X8BdBd5aozY7GUpTRXkDy0bvadgWwe6asVP7iC1Z-tCybu55DcWqdakWQ675eI6k3oB-DkTF09HOs_q3V3BTo-nqg1mOoeYuiV7M4U-sxlvzh8QBLfoM1U7HntWygD_k9qIJzQKqoMan_0pcaw5XrI12Nrnc8Yw-_Zh6wi194oqUOZIWpuS3CCRxdnNqeNw",
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
    2025-12-03 14:53:07,869 INFO  traceId=, parentId=, spanId=, sampled= [or.je.ro.BasicAuthSSOProxyRoute] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup) Incoming headers: {Accept=*/*, Authorization=Basic SmlKaTpQIXNzdzByZA==, CamelHttpAuthentication=Basic, CamelHttpHost=echo-api.3scale.net, CamelHttpMethod=GET, CamelHttpPath=/demo, CamelHttpPort=443, CamelHttpQuery=null, CamelHttpRawQuery=null, CamelHttpScheme=https, CamelHttpUri=/demo, CamelHttpUrl=https://echo-api.3scale.net/demo, CamelNettyChannelHandlerContext=ChannelHandlerContext(handler, [id: 0x7378e839, L:/[0:0:0:0:0:0:0:1]:9443 - R:/[0:0:0:0:0:0:0:1]:57483]), CamelNettyLocalAddress=/[0:0:0:0:0:0:0:1]:9443, CamelNettyRemoteAddress=/[0:0:0:0:0:0:0:1]:57483, CamelNettySSLSession=Session(1764769987821|TLS_AES_256_GCM_SHA384), Connection=close, content-length=0, Host=echo-api.3scale.net}
    2025-12-03 14:53:08,460 DEBUG traceId=, parentId=, spanId=, sampled= [io.qu.oi.cl.ru.OidcClientImpl] (vert.x-eventloop-thread-6) Default OidcClient has acquired the tokens
    2025-12-03 14:53:08,464 INFO  traceId=, parentId=, spanId=, sampled= [or.je.ro.BasicAuthSSOProxyRoute] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup) Headers propagated to the backend: {Accept=*/*, Authorization=Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6bXRzSnd0WWxBd2JxOHplMlZNWDZDU0hOMG9OcW5scU0zbDBwX1dBWm1vIn0.eyJleHAiOjE3NjQ3NzAyODgsImlhdCI6MTc2NDc2OTk4OCwianRpIjoib25ydHJvOmVjYTBhNWViLTk2MDItNWQ2Mi1iZjNmLTJmODg0MjBhZWRkMiIsImlzcyI6Imh0dHBzOi8vc3NvLmFwcHMub2NwNC5qbnlpbGltYi5ldS9yZWFsbXMvZGVtbyIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJjNzU2NGVkYS1jYWQ0LTQ3MDItOWE5OS03MDk0YmZhNWE5MTciLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJiYXNpYy1hdXRoLXNzby1wcm94eSIsInNpZCI6IjA0ZjdmNGM2LTUyY2UtNzYwYy0xZDhmLTY4ZDFmZTgxOWU4MyIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiLyoiXSwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbIm9mZmxpbmVfYWNjZXNzIiwiZGVmYXVsdC1yb2xlcy1kZW1vIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJzY29wZSI6ImVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkppSmkgS2FyYW1lbGEiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJqaWppIiwiZ2l2ZW5fbmFtZSI6IkppSmkiLCJmYW1pbHlfbmFtZSI6IkthcmFtZWxhIiwiZW1haWwiOiJqbnlpbGltYitqaWppQHJlZGhhdC5jb20ifQ.K0lxUfwD0Jdl06htmlCVVnVm7-uXELUUOrPRzDdkREEB5fYxSh-Sm28J7qmF4aUXxu5fMz6-o58plTywwZtTkZwgqqRdOfv1LbhD02N-_Rpq4iQlJPGZEmfBn3_TAUlQEjyRhLZ_opkeeflUjZSiaA0X8BdBd5aozY7GUpTRXkDy0bvadgWwe6asVP7iC1Z-tCybu55DcWqdakWQ675eI6k3oB-DkTF09HOs_q3V3BTo-nqg1mOoeYuiV7M4U-sxlvzh8QBLfoM1U7HntWygD_k9qIJzQKqoMan_0pcaw5XrI12Nrnc8Yw-_Zh6wi194oqUOZIWpuS3CCRxdnNqeNw, CamelHttpAuthentication=Basic, CamelHttpHost=echo-api.3scale.net, CamelHttpMethod=GET, CamelHttpPath=/demo, CamelHttpPort=443, CamelHttpQuery=null, CamelHttpRawQuery=null, CamelHttpScheme=https, CamelHttpUri=/demo, CamelHttpUrl=https://echo-api.3scale.net/demo, CamelNettyChannelHandlerContext=ChannelHandlerContext(handler, [id: 0x7378e839, L:/[0:0:0:0:0:0:0:1]:9443 - R:/[0:0:0:0:0:0:0:1]:57483]), CamelNettyLocalAddress=/[0:0:0:0:0:0:0:1]:9443, CamelNettyRemoteAddress=/[0:0:0:0:0:0:0:1]:57483, CamelNettySSLSession=Session(1764769987821|TLS_AES_256_GCM_SHA384), Connection=close, content-length=0, Host=echo-api.3scale.net}
    2025-12-03 14:53:08,496 INFO  traceId=, parentId=, spanId=, sampled= [or.ap.ca.co.ne.ht.HttpClientInitializerFactory] (Camel (camel-1) thread #5 - NettyConsumerExecutorGroup) Created SslContext javax.net.ssl.SSLContext@2f109101
    ```

## Packaging and running the application on Red Hat OpenShift

### Pre-requisites

- Access to a [Red Hat OpenShift](https://access.redhat.com/documentation/en-us/openshift_container_platform) cluster v4
- User has self-provisioner privilege or has access to a working OpenShift project
- **OPTIONAL**: [**Jaeger**](https://www.jaegertracing.io/), a distributed tracing system for observability ([_open tracing_](https://opentracing.io/)).

### Instructions to package using Quarkus JVM mode and deploy to OpenShift

1. Login to the OpenShift cluster
    ```shell
    oc login ...
    ```
2. Create an OpenShift project or use your existing OpenShift project. For instance, to create `ceq-services-jvm`
    ```shell
    oc new-project ceq-services-jvm --display-name="Red Hat build of Apache Camel for Quarkus Apps - JVM Mode"
    ```
3. Create secret containing the keystore
    ```shell
    oc create secret generic basic-auth-sso-proxy-keystore-secret \
    --from-file=keystore.p12=./tls-keys/keystore.p12
    ```
4. Adjust the `quarkus.otel.exporter.otlp.endpoint` property of the `basic-auth-sso-proxy-secret` in the [`openshift.yml`](./src/main/kubernetes/openshift.yml) file according to your OpenShift environment and where you installed the [_Jaeger_](https://www.jaegertracing.io/) server.
5. Deploy to OpenShift using the _**S2I binary workflow**_
    ```shell
    ./mvnw clean package -Dquarkus.openshift.deploy=true
    ```

###  ALTERNATIVE - Instructions to package using Quarkus native mode and deploy to OpenShift

> :warning: **Pre-requisites**
- For native compilation, a Linux X86_64 operating system or an OCI (Open Container Initiative) compatible container runtime, such as Podman or Docker is required.

1. Login to the OpenShift cluster
    ```shell
    oc login ...
    ```
2. Create an OpenShift project or use your existing OpenShift project. For instance, to create `ceq-services-native`
    ```shell
    oc new-project ceq-services-native --display-name="Red Hat build of Apache Camel for Quarkus Apps - Native Mode"
    ```
3. Create secret containing the keystore
    ```shell
    oc create secret generic basic-auth-sso-proxy-keystore-secret \
    --from-file=keystore.p12=./tls-keys/keystore.p12
    ```
4. Adjust the `quarkus.otel.exporter.otlp.endpoint` property of the `threescale-camel-service-secret` in the [`openshift.yml`](./src/main/kubernetes/openshift.yml) file according to your OpenShift environment and where you installed the [_Jaeger_](https://www.jaegertracing.io/) server.
5. Package and deploy to OpenShift using the _**S2I binary workflow**_:
    ```shell
    ./mvnw clean package -Pnative \
    -Dquarkus.openshift.deploy=true
    ```

## How to configure the _APIcast Camel Service_ policy to use this service

###  Pre-requisite

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
http -v -a JiJi https://echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu/v2/echo/demo
```
```shell
http: password for JiJi@echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu:: 
GET /v2/echo/demo HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Authorization: Basic SmlKaTpQIXNzdzByZA==
Connection: keep-alive
Host: echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu
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
        "HTTP_AUTHORIZATION": "Basic SmlKaTpQIXNzdzByZA==,Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ6bXRzSnd0WWxBd2JxOHplMlZNWDZDU0hOMG9OcW5scU0zbDBwX1dBWm1vIn0.eyJleHAiOjE3MzM4NTU3NTksImlhdCI6MTczMzg1NTQ1OSwianRpIjoiMGU5NGY4MDUtYjU1MC00MmIwLTlmMWMtYTMwOGY5NGFiZDViIiwiaXNzIjoiaHR0cHM6Ly9zc28uYXBwcy5vY3A0LmpueWlsaW1iLmV1L3JlYWxtcy9kZW1vIiwiYXVkIjoiYWNjb3VudCIsInN1YiI6ImM3NTY0ZWRhLWNhZDQtNDcwMi05YTk5LTcwOTRiZmE1YTkxNyIsInR5cCI6IkJlYXJlciIsImF6cCI6ImJhc2ljLWF1dGgtc3NvLXNlcnZpY2UiLCJzaWQiOiI3NmNjNTA2NC1hNjlkLTQ4OTktYjYzYy04NWY4Nzc1MjFmMzgiLCJhY3IiOiIxIiwiYWxsb3dlZC1vcmlnaW5zIjpbIi8qIl0sInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJvZmZsaW5lX2FjY2VzcyIsImRlZmF1bHQtcm9sZXMtZGVtbyIsInVtYV9hdXRob3JpemF0aW9uIl19LCJyZXNvdXJjZV9hY2Nlc3MiOnsiYWNjb3VudCI6eyJyb2xlcyI6WyJtYW5hZ2UtYWNjb3VudCIsIm1hbmFnZS1hY2NvdW50LWxpbmtzIiwidmlldy1wcm9maWxlIl19fSwic2NvcGUiOiJlbWFpbCBwcm9maWxlIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsIm5hbWUiOiJKaUppIEthcmFtZWxhIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiamlqaSIsImdpdmVuX25hbWUiOiJKaUppIiwiZmFtaWx5X25hbWUiOiJLYXJhbWVsYSIsImVtYWlsIjoiam55aWxpbWIramlqaUByZWRoYXQuY29tIn0.n1wzRHignLv2ipeRfq_gVSDF_MAoxequ5ar-KCvP_7Yzh67E7jW6DwgTotoMenRDfyPk_ZXCf8UUQresp3JZ4xGGqVD1SixUqBAtkOBuF9BX-L8zLS43iGfM-wiz65GDcgrnxfeGyDpvnT0unu3MOD-sh5As0d60R-QQPYx96oLYa4e1bSjXyk8MhbaVktyEO-l7Lz7BuiTPqvHlfcUOhp8sOmT_k1d_QlYpYYf604WVyV4UFPVWfhq0QCJ4OOzeLb3rtrqFbbCIDkCZF0ICRSy-QeypabahQoXWqPUeTW_93yxMZp1P4-mJxkw1VrL_0YhhRwBj3tEWK5fkXlXSiw",
        "HTTP_FORWARDED": "for=41.250.31.16;host=echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu;proto=https, for=41.250.31.16;host=echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu;proto=https",
        "HTTP_HOST": "echo-api.3scale.net",
        "HTTP_TRACEPARENT": "00-5bc35ff31b02eb8261aa16f803a5029f-3f54bc2887e63fc6-01",
        "HTTP_USER_AGENT": "HTTPie/3.2.4,HTTPie/3.2.4",
        "HTTP_VERSION": "HTTP/1.1",
        "HTTP_X_ENVOY_EXPECTED_RQ_TIMEOUT_MS": "15000",
        "HTTP_X_ENVOY_EXTERNAL_ADDRESS": "168.119.33.94",
        "HTTP_X_FORWARDED_FOR": "41.250.31.16,41.250.31.16,168.119.33.94",
        "HTTP_X_FORWARDED_HOST": "echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu,echo-api-v2-apim-demo-apicast-production.apps.ocp4.jnyilimb.eu",
        "HTTP_X_FORWARDED_PORT": "443, 443",
        "HTTP_X_FORWARDED_PROTO": "https",
        "HTTP_X_REQUEST_ID": "816483e9-9321-4588-b1d6-d1bd50971a86"
    },
    "method": "GET",
    "path": "/demo",
    "uuid": "2c6c90a9-d398-43aa-8d63-bdcf3d1ebaea"
}
```