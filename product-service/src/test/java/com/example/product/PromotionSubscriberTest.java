package com.example.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PromotionSubscriberTest {
    CacheManager manager=mock(CacheManager.class);
    Cache cache=mock(Cache.class);
    PromotionSubscriber subscriber=new PromotionSubscriber(new ObjectMapper(),manager);
    Message message(String body) {
        Message message=mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
    @Test void evictsOnlyRequestedProductAndRedeliveryIsSafe() {
        when(manager.getCache("products")).thenReturn(cache);
        subscriber.onMessage(message("{\"product_id\":42}"),null);
        subscriber.onMessage(message("{\"product_id\":42}"),null);
        verify(cache,times(2)).evict(42L);
        verify(cache,never()).clear();
        verifyNoMoreInteractions(cache);
    }
    @Test void invalidMessagesNeverEvict() {
        for(String value:new String[]{"not-json","{}","null","{\"product_id\":-1}"})
            subscriber.onMessage(message(value),null);
        verifyNoInteractions(manager,cache);
    }
    @Test void evictionFailureIsNotSilentlyIgnored() {
        when(manager.getCache("products")).thenReturn(cache);
        doThrow(new IllegalStateException("Redis offline")).when(cache).evict(42L);
        assertThatThrownBy(()->subscriber.onMessage(message("{\"product_id\":42}"),null))
            .isInstanceOf(IllegalStateException.class);
    }
}
