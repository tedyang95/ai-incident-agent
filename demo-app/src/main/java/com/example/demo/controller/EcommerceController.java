package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.model.Order;
import com.example.demo.config.FaultState;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E-commerce API controller.
 * <p>
 * Exposes the normal business endpoints plus fault-injection endpoints used to
 * trigger alerts so the AI agent has realistic scenarios to analyze:
 * <ul>
 *   <li>/admin/fail/error   → start returning 500s (triggers HighErrorRate)</li>
 *   <li>/admin/fail/latency → inject 2-5s delays (triggers HighLatency)</li>
 *   <li>/admin/fail/memory  → start a memory leak (triggers HighMemoryUsage)</li>
 *   <li>/admin/fail/stop    → stop all active faults</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class EcommerceController {

    private static final Logger log = LoggerFactory.getLogger(EcommerceController.class);
    private final Random random = new Random();
    private final FaultState faultState;
    private final Map<Long, Product> products = new ConcurrentHashMap<>();
    private final Map<Long, Order> orders = new ConcurrentHashMap<>();

    public EcommerceController(FaultState faultState) {
        this.faultState = faultState;
        // Seed some sample catalog data.
        products.put(1L, new Product(1L, "Laptop", 999.99, 50));
        products.put(2L, new Product(2L, "Phone", 699.99, 100));
        products.put(3L, new Product(3L, "Headphones", 199.99, 200));
    }

    // ============================================================
    // Business endpoints
    // ============================================================

    @GetMapping("/products")
    @Timed(value = "api.products.list", description = "Time taken to list products")
    public ResponseEntity<List<Product>> listProducts() {
        log.info("Listing all products, count={}", products.size());
        simulateLatencyIfEnabled();
        return ResponseEntity.ok(List.copyOf(products.values()));
    }

    @GetMapping("/products/{id}")
    @Timed(value = "api.products.get", description = "Time taken to get a product")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        simulateLatencyIfEnabled();
        Product product = products.get(id);
        if (product == null) {
            log.warn("Product not found: id={}", id);
            return ResponseEntity.notFound().build();
        }
        log.info("Retrieved product: id={}, name={}", id, product.name());
        return ResponseEntity.ok(product);
    }

    @PostMapping("/orders")
    @Timed(value = "api.orders.create", description = "Time taken to create an order")
    public ResponseEntity<Order> createOrder(@RequestBody Map<String, Object> request) {
        simulateLatencyIfEnabled();
        throwErrorIfEnabled();

        Long productId = Long.valueOf(request.get("productId").toString());
        int quantity = Integer.parseInt(request.get("quantity").toString());
        Product product = products.get(productId);

        if (product == null) {
            log.error("Order failed: product not found, productId={}", productId);
            return ResponseEntity.badRequest().build();
        }

        double total = product.price() * quantity;
        Order order = new Order(System.currentTimeMillis(), productId, quantity, total, "CREATED");
        orders.put(order.id(), order);
        log.info("Order created: id={}, product={}, qty={}, total={}",
                order.id(), product.name(), quantity, total);
        return ResponseEntity.ok(order);
    }

    @GetMapping("/orders/{id}")
    @Timed(value = "api.orders.get", description = "Time taken to get an order")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) {
        simulateLatencyIfEnabled();
        Order order = orders.get(id);
        if (order == null) {
            log.warn("Order not found: id={}", id);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "demo-ecommerce",
                "faults", faultState.getActiveFaults().toString()
        ));
    }

    // ============================================================
    // Fault-injection endpoints
    // Used to trigger alerts and exercise the agent's root-cause analysis.
    // ============================================================

    @PostMapping("/admin/fail/error")
    public ResponseEntity<Map<String, String>> enableErrorFault() {
        faultState.setErrorEnabled(true);
        log.warn("FAULT INJECTION: Error fault ENABLED - all POST /api/orders will return 500");
        return ResponseEntity.ok(Map.of("fault", "error", "status", "enabled"));
    }

    @PostMapping("/admin/fail/latency")
    public ResponseEntity<Map<String, String>> enableLatencyFault() {
        faultState.setLatencyEnabled(true);
        log.warn("FAULT INJECTION: Latency fault ENABLED - all requests will have 2-5s delay");
        return ResponseEntity.ok(Map.of("fault", "latency", "status", "enabled"));
    }

    @PostMapping("/admin/fail/memory")
    public ResponseEntity<Map<String, String>> enableMemoryFault() {
        faultState.setMemoryLeakEnabled(true);
        log.warn("FAULT INJECTION: Memory leak fault ENABLED - allocating memory without release");
        return ResponseEntity.ok(Map.of("fault", "memory", "status", "enabled"));
    }

    @PostMapping("/admin/fail/stop")
    public ResponseEntity<Map<String, String>> stopAllFaults() {
        faultState.reset();
        log.info("FAULT INJECTION: All faults STOPPED");
        return ResponseEntity.ok(Map.of("status", "all faults stopped"));
    }

    @GetMapping("/admin/fail/status")
    public ResponseEntity<FaultState> getFaultStatus() {
        return ResponseEntity.ok(faultState);
    }

    // ============================================================
    // Internals
    // ============================================================

    private void simulateLatencyIfEnabled() {
        if (faultState.isLatencyEnabled()) {
            try {
                int delay = 2000 + random.nextInt(3000); // 2-5 s delay
                log.debug("Latency fault: sleeping {}ms", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void throwErrorIfEnabled() {
        if (faultState.isErrorEnabled()) {
            log.error("Error fault injected: throwing RuntimeException for order creation");
            throw new RuntimeException("Injected fault: database connection timeout (simulated)");
        }
    }
}
