package boutique.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Versionnement de l'API (Spring Framework 7) : le client choisit la version avec l'en-tete API-Version.
 * Sans en-tete, il recoit la version 1 : les anciens clients continuent de marcher.
 */
@Configuration
public class Versions implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader("API-Version")
                .addSupportedVersions("1", "2")
                .setDefaultVersion("1");
    }
}
