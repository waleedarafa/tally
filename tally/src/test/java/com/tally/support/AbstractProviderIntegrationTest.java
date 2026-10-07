package com.tally.support;

import com.tally.AbstractIntegrationTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Integration tests that reach the fake provider over the network. */
public abstract class AbstractProviderIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry properties) {
        properties.add("tally.payment.provider.base-url", FakeProvider::baseUrl);
    }
}
