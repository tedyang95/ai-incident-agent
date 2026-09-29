package com.example.demo.model;

/**
 * 商品模型（Product model）
 */
public record Product(Long id, String name, double price, int stock) {
}
