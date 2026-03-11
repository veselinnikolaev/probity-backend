package me.veselin.probity;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ProbityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbityApplication.class, args);
    }

    @Bean
    CommandLineRunner seedUsers() {
        return args -> {

        };
    }
}
