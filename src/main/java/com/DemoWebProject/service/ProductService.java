// src/main/java/com/example/service/ProductService.java

package com.DemoWebProject.service;

import com.DemoWebProject.model.Product;

import java.util.List;

public interface ProductService {
    List<Product> getAllProducts();

    Product getProductById(Long id);

    Product saveProduct(Product product);
    
    Product updateProduct(Long id, Product product);

    void deleteProduct(Long id);
}
