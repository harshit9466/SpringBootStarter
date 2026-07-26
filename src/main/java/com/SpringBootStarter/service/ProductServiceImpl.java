package com.SpringBootStarter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.SpringBootStarter.exception.ProductNotFoundException;
import com.SpringBootStarter.model.Product;
import com.SpringBootStarter.repository.ProductRepository;

import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;
    private final MeterRegistry     meterRegistry;

    /*
     * Counter: products.created.total
     * Increments by 1 every time a product is saved successfully.
     * Use in Prometheus/Grafana: rate(products_created_total[5m])
     * → "How many products are being created per second right now?"
     *
     * Why pre-build Counters in constructor instead of inline?
     * Counter.builder(...).register(meterRegistry) creates/retrieves from registry.
     * Calling it on every request is safe (idempotent) but slightly wasteful.
     * Pre-building avoids repeated registry lookups on hot paths.
     */
    private final Counter productsCreatedCounter;
    private final Counter productsDeletedCounter;
    private final Counter productNotFoundCounter;

    /*
     * Timer: product.operation.duration
     * Measures how long each operation takes.
     * Automatically tracks: count, total time, max time, AND percentile buckets.
     * Use tags to split by operation type — one Timer, multiple dimensions.
     *
     * publishPercentiles(0.5, 0.95, 0.99):
     * → Tells Micrometer to pre-compute and publish p50, p95, p99.
     *   These appear as separate gauge metrics in Prometheus.
     *   Without this: only count and sum available — you cannot compute percentiles.
     *
     * publishPercentileHistogram(true):
     * → Publishes histogram buckets — lets Prometheus compute percentiles
     *   on its side using histogram_quantile() function.
     *   More flexible but higher cardinality cost in Prometheus.
     */
    private final Timer getByIdTimer;
    private final Timer getAllTimer;
    private final Timer saveTimer;

    public ProductServiceImpl(ProductRepository productRepository, MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.meterRegistry     = meterRegistry;

        this.productsCreatedCounter = Counter.builder("products.created.total")
                .description("Total number of products successfully created")
                .register(meterRegistry);

        this.productsDeletedCounter = Counter.builder("products.deleted.total")
                .description("Total number of products successfully deleted")
                .register(meterRegistry);

        /*
         * productNotFoundCounter — business-level error metric.
         * This is distinct from HTTP 404 metric (which Spring auto-instruments).
         * This one specifically tracks "product lookup failed" — useful for:
         * 1. Detecting stale product IDs being requested (data consistency issue)
         * 2. Alerting if not-found rate spikes (possible data loss or bad deployment)
         */
        this.productNotFoundCounter = Counter.builder("products.not_found.total")
                .description("Total number of product lookups that returned not found")
                .register(meterRegistry);

        this.getByIdTimer = Timer.builder("product.operation.duration")
                .description("Duration of product service operations")
                .tag("operation", "getById")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.getAllTimer = Timer.builder("product.operation.duration")
                .description("Duration of product service operations")
                .tag("operation", "getAll")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.saveTimer = Timer.builder("product.operation.duration")
                .description("Duration of product service operations")
                .tag("operation", "save")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public List<Product> getAllProducts() {
        /*
         * Timer.record(Supplier<T>):
         * Starts timer → runs lambda → stops timer → returns result.
         * Cleaner than Timer.Sample start/stop when you have a return value.
         * The time includes: DB call + any object mapping overhead.
         */
        return getAllTimer.record(() -> productRepository.findAll());
    }

    @Override
    public Product getProductById(Long id) {
        return getByIdTimer.record(() -> {
            try {
                return productRepository.findById(id)
                        .orElseThrow(() -> {
                            /*
                             * Increment BEFORE throwing — the exception may be caught
                             * upstream and counter will still be updated correctly.
                             * If you increment after orElseThrow, the counter line
                             * never executes.
                             */
                            productNotFoundCounter.increment();
                            log.warn("Product not found. productId={}", id);
                            return new ProductNotFoundException("Product not found with id: " + id);
                        });
            } catch (ProductNotFoundException e) {
                throw e;
            }
        });
    }

    @Override
    public Product saveProduct(Product product) {
        Product saved = saveTimer.record(() -> productRepository.save(product));
        productsCreatedCounter.increment();
        log.info("Product saved. productId={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Override
    public Product updateProduct(Long id, Product updatedProduct) {
        Product existing = getProductById(id);
        existing.setName(updatedProduct.getName());
        existing.setDescription(updatedProduct.getDescription());
        existing.setPrice(updatedProduct.getPrice());
        Product saved = productRepository.save(existing);
        log.info("Product updated. productId={}", id);
        return saved;
    }

    @Override
    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
        productsDeletedCounter.increment();
        log.info("Product deleted. productId={}", id);
    }
}
