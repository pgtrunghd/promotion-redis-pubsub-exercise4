package com.example.promotion;

import java.math.BigDecimal;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PromotionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    public PromotionService(JdbcTemplate jdbc,TransactionTemplate transaction,StringRedisTemplate redis,ObjectMapper mapper) {
        this.jdbc=jdbc;this.transaction=transaction;this.redis=redis;this.mapper=mapper;
    }
    public record Product(Long id,String name,BigDecimal price) {}
    public record Published(Long productId,BigDecimal price,long subscribers) {}

    public Product create(String name,BigDecimal price) {
        Long id=jdbc.queryForObject("INSERT INTO products(name,price) VALUES (?,?) RETURNING id",Long.class,name,price);
        return new Product(id,name,price);
    }

    public Published changePrice(Long id,BigDecimal price) {
        final String payload;
        try { payload=mapper.writeValueAsString(Map.of("product_id",id)); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize event",e); }
        transaction.executeWithoutResult(status -> {
            int changed=jdbc.update("UPDATE products SET price=? WHERE id=?",price,id);
            if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy sản phẩm");
        });
        // executeWithoutResult returns only AFTER commit, so a subscriber cannot reload uncommitted price.
        Long subscribers=redis.convertAndSend("promotion-updates",payload);
        return new Published(id,price,subscribers==null?0:subscribers);
    }
}
