package com.example.product;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service=service; }
    @GetMapping("/{id}")
    public ProductPrice get(@PathVariable Long id) { return service.get(id); }
}
