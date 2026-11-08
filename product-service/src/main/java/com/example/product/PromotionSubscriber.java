package com.example.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class PromotionSubscriber implements MessageListener {
    private static final Logger log=LoggerFactory.getLogger(PromotionSubscriber.class);
    private final ObjectMapper mapper;
    private final CacheManager cacheManager;
    public PromotionSubscriber(ObjectMapper mapper,CacheManager cacheManager) {
        this.mapper=mapper;this.cacheManager=cacheManager;
    }
    public record Update(@JsonProperty("product_id") Long productId) {}

    @Override
    public void onMessage(Message message,byte[] pattern) {
        final Update update;
        try {
            update=mapper.readValue(message.getBody(),Update.class);
            if(update==null || update.productId()==null || update.productId()<=0) {
                log.warn("Ignoring invalid promotion update");
                return;
            }
        } catch (Exception e) {
            log.warn("Ignoring malformed promotion update",e);
            return;
        }
        // Same cache manager/key serializer as @Cacheable. Eviction failures propagate to container logging.
        var cache=cacheManager.getCache("products");
        if(cache==null) throw new IllegalStateException("products cache is not configured");
        cache.evict(update.productId());
        log.info("Evicted product cache id={}",update.productId());
    }
}
