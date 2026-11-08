package com.example.product;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
    private final JdbcTemplate jdbc;
    public ProductService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Cacheable(cacheNames="products",key="#id")
    public ProductPrice get(Long id) {
        return jdbc.query("SELECT id,name,price FROM products WHERE id=?",
                (rs,row) -> new ProductPrice(rs.getLong("id"),rs.getString("name"),rs.getBigDecimal("price")),id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy sản phẩm"));
    }
}
