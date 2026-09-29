package com.example.demo.model;

/**
 * 订单模型（Order model）
 */
public record Order(Long id, Long productId, int quantity, double total, String status) {
}
