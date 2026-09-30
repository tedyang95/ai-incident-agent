package com.example.demo.model;

/**
 * Product entity as offered in the catalog.
 */
public record Product(Long id, String name, double price, int stock) {
}
