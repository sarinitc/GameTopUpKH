package org.example.gametopupkh.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gameTopUpOpenApi() {
        return new OpenAPI().info(new Info()
                .title("GameTopUp API")
                .description("Backend REST API for the GameTopUp learning project")
                .version("v1"));
    }
}
