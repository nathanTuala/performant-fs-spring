package com.performantfs.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Base for end-to-end tests: the real server on a random port, backed by a real Postgres
 * (Testcontainers), exercised over HTTP. Every test class shares one context and one database;
 * tests stay independent by working as their own freshly registered users.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    /** A client with no session. */
    protected ApiClient anonymous() {
        return new ApiClient("http://localhost:" + port);
    }
}
