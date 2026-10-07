package com.tally.support;

import com.tally.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests that reach the fake provider over the network. One provider container serves
 * the whole run, so each test starts with the provider in NORMAL mode.
 */
public abstract class AbstractProviderIntegrationTest extends AbstractIntegrationTest {

    @BeforeEach
    void providerBehavesNormally() {
        FakeProvider.mode("NORMAL");
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry properties) {
        properties.add("tally.payment.provider.base-url", FakeProvider::baseUrl);
    }
}
