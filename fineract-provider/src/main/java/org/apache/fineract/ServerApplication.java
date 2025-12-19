package org.apache.fineract;

import java.io.IOException;
import java.time.Duration;

import org.apache.fineract.infrastructure.core.boot.FineractLiquibaseOnlyApplicationConfiguration;
import org.apache.fineract.infrastructure.core.boot.FineractWebApplicationConfiguration;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// Redis Imports
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

public class ServerApplication extends SpringBootServletInitializer {

    @EnableCaching
    @Import({ FineractWebApplicationConfiguration.class, FineractLiquibaseOnlyApplicationConfiguration.class })
    private static final class Configuration {}

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return configureApplication(builder);
    }

    private static SpringApplicationBuilder configureApplication(SpringApplicationBuilder builder) {
        return builder.sources(Configuration.class);
    }

    public static void main(String[] args) throws IOException {
        configureApplication(new SpringApplicationBuilder(ServerApplication.class)).run(args);
    }

    /**
     * SPECIFIC REDIS MANAGER
     * We name this bean "redisCacheManager" so we can call it explicitly
     * without conflicting with Fineract's default EhCache.
     */
    @Bean(name = "redisCacheManager")
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    // SPY: Checks if our specific bean is loaded
    @Bean
    public CommandLineRunner reportCacheManager() {
        return args -> {
            System.out.println("\n\n=========================================================");
            System.out.println("REDIS MANAGER LOADED: TRUE");
            System.out.println("=========================================================\n\n");
        };
    }
}