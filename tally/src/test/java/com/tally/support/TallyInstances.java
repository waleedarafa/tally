package com.tally.support;

import com.tally.AbstractIntegrationTest;
import com.tally.TallyApplication;
import com.tally.platform.TestClockConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts whole Tally instances by hand on the shared test database, for tests where a crash and a
 * restart must be separate application instances (Spike A, AT-7). Each has its own TestClock.
 */
public final class TallyInstances implements AutoCloseable {

    private final List<ConfigurableApplicationContext> running = new ArrayList<>();

    public ConfigurableApplicationContext start(String name, Class<?> extraConfiguration, String... properties) {
        AbstractIntegrationTest.POSTGRES.start();
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", AbstractIntegrationTest.POSTGRES.getJdbcUrl());
        props.put("spring.datasource.username", AbstractIntegrationTest.POSTGRES.getUsername());
        props.put("spring.datasource.password", AbstractIntegrationTest.POSTGRES.getPassword());
        props.put("spring.flyway.locations", "classpath:db/migration,classpath:db/testmigration");
        props.put("tally.platform.polling-enabled", "false");
        props.put("spring.application.name", name);
        // The caller's settings replace these defaults: a repeated argument would bind as "a,b".
        for (String property : properties) {
            String[] keyValue = property.split("=", 2);
            props.put(keyValue[0], keyValue[1]);
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                TallyApplication.class, TestClockConfiguration.class, extraConfiguration)
                .web(WebApplicationType.NONE)
                // As command-line arguments: builder "properties" are defaults that application.yml overrides.
                .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        running.add(context);
        return context;
    }

    /** Stops an instance; its listeners still blocked stay blocked, as after a crash. */
    public void stop(ConfigurableApplicationContext context) {
        context.close();
        running.remove(context);
    }

    @Override
    public void close() {
        running.forEach(ConfigurableApplicationContext::close);
        running.clear();
    }
}
