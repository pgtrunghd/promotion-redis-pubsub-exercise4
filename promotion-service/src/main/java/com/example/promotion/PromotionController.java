package com.example.promotion;

import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class PromotionController {
    private final PromotionService service;
    public PromotionController(PromotionService service) { this.service=service; }
    public record NewProduct(@NotBlank @Size(max=255) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer=12,fraction=2) BigDecimal price) {}
    public record Price(@NotNull @DecimalMin("0.00") @Digits(integer=12,fraction=2) BigDecimal price) {}

    @PostMapping("/api/products")
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionService.Product create(@Valid @RequestBody NewProduct request) {
        return service.create(request.name(),request.price());
    }
    @PutMapping("/api/promotions/products/{id}/price")
    public PromotionService.Published change(@PathVariable Long id,@Valid @RequestBody Price request) {
        return service.changePrice(id,request.price());
    }
}
