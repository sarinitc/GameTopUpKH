package org.example.gametopupkh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class GameTopUpKhApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameTopUpKhApplication.class, args);
    }
}
