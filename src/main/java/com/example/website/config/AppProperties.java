package com.example.website.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Admin admin = new Admin();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expireMinutes = 720;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins;
    }

    @Getter
    @Setter
    public static class Admin {
        private String defaultUsername = "admin";
        private String defaultPassword = "admin123";
    }
}
