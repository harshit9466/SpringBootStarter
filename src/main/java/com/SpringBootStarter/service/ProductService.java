// src/main/java/com/example/service/ProductService.java

package com.SpringBootStarter.service;

import java.util.List;

import com.SpringBootStarter.model.Product;

public interface ProductService {
    List<Product> getAllProducts();

    Product getProductById(Long id);

    Product saveProduct(Product product);
    
    Product updateProduct(Long id, Product product);

    void deleteProduct(Long id);
}
