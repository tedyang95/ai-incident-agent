package com.example.demo.model;

/**
 * Order entity representing a customer purchase.
 */
public record Order(Long id, Long productId, int quantity, double total, String status) {
}
