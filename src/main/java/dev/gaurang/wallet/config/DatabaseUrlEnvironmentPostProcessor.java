package dev.gaurang.wallet.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Managed Postgres add-ons hand out a postgres://user:pass@host/db URL, which JDBC cannot parse.
 * Translating it here means the deploy needs no hand-copied credentials.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || !databaseUrl.startsWith("postgres")) {
            return;
        }
        URI uri = URI.create(databaseUrl);
        String[] credentials = uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://%s:%d%s".formatted(
                uri.getHost(), uri.getPort() == -1 ? 5432 : uri.getPort(), uri.getPath()));
        if (credentials.length == 2) {
            properties.put("spring.datasource.username", credentials[0]);
            properties.put("spring.datasource.password", credentials[1]);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("databaseUrl", properties));
    }
}
