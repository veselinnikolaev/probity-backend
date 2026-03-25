package me.veselin.probity;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@EnableCaching
public class ProbityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbityApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(Environment environment) {
        return args -> {
            System.out.println(System.getenv("DB_USERNAME"));
            System.out.println(environment.getProperty("spring.datasource.username"));
        };
    }
}
