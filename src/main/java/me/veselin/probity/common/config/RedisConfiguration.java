package me.veselin.probity.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfiguration {
    @Value( "${spring.data.redis.username}")
    private String username;
    @Value( "${spring.data.redis.database}")
    private int database;
    @Value( "${spring.data.redis.password}")
    private String password;
    @Value( "${spring.data.redis.host}")
    private String host;
    @Value( "${spring.data.redis.port}")
    private int port;

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();

        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    LettuceConnectionFactory lettuceConnectionFactory() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(host, port);
        config.setPassword(password);
        config.setUsername(username);
        config.setDatabase(database);
        return new LettuceConnectionFactory(config);
    }
}
