package de.fubo.appserver.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "fubo")
public record FuboProperties(@NotNull Session session, @NotNull Cors cors) {

    /** Attribute des Session-Cookies. */
    public record Session(@NotBlank String cookieName,
                          boolean cookieSecure,
                          @NotBlank String cookieSameSite) {}

    /** Erlaubte Frontend-Origins; leer ist unzulaessig. */
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {}
}
