package com.example.product;

import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.data.redis.serializer.*;

@Configuration
@EnableCaching
public class RedisConfig {
    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        var config=RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)).disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(ProductPrice.class)));
        return RedisCacheManager.builder(connectionFactory).cacheDefaults(config)
                .withCacheConfiguration("products",config).build();
    }
    @Bean
    RedisMessageListenerContainer listener(RedisConnectionFactory connectionFactory,PromotionSubscriber subscriber) {
        var container=new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber,new ChannelTopic("promotion-updates"));
        return container;
    }
}
