package com.example.promotion;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PromotionServiceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    TransactionTemplate transaction=mock(TransactionTemplate.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    PromotionService service=new PromotionService(jdbc,transaction,redis,new ObjectMapper());
    BigDecimal price=new BigDecimal("79.00");
    @Test void publishesProductIdOnlyAfterTransactionReturns() {
        AtomicBoolean committed=new AtomicBoolean();
        when(jdbc.update("UPDATE products SET price=? WHERE id=?",price,1L)).thenReturn(1);
        doAnswer(i->{Consumer<TransactionStatus> work=i.getArgument(0);work.accept(mock(TransactionStatus.class));committed.set(true);return null;})
            .when(transaction).executeWithoutResult(any());
        when(redis.convertAndSend(eq("promotion-updates"),anyString())).thenAnswer(i->{
            assertThat(committed).isTrue();
            assertThat(new ObjectMapper().readTree((String)i.getArgument(1)).get("product_id").asLong()).isEqualTo(1L);
            return 2L;
        });
        assertThat(service.changePrice(1L,price).subscribers()).isEqualTo(2);
        var order=inOrder(jdbc,redis);
        order.verify(jdbc).update("UPDATE products SET price=? WHERE id=?",price,1L);
        order.verify(redis).convertAndSend(eq("promotion-updates"),anyString());
    }
    @Test void failedCommitDoesNotPublish() {
        doThrow(new IllegalStateException("DB commit failed")).when(transaction).executeWithoutResult(any());
        assertThatThrownBy(()->service.changePrice(1L,price)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redis);
    }
    @Test void nonexistentProductDoesNotPublish() {
        doAnswer(i->{Consumer<TransactionStatus> work=i.getArgument(0);work.accept(mock(TransactionStatus.class));return null;})
            .when(transaction).executeWithoutResult(any());
        assertThatThrownBy(()->service.changePrice(1L,price)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(redis);
    }
}
