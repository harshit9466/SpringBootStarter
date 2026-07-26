// src/main/java/com/example/controller/ProductController.java

package com.SpringBootStarter.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.SpringBootStarter.model.Product;
import com.SpringBootStarter.service.ProductService;
import com.SpringBootStarter.util.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);
	
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }
    
//	  use this is you don't want to use the constructor for injecting the dependencies.
//    @Autowired
//    private ProductService productService;


    @GetMapping("/home")
    //or this below way is also doing the same thing 
//    @RequestMapping(path = "/home", method = RequestMethod.GET)
    public String home() {
        log.info("Health check endpoint called");
        return "This is Home.";
    }
    
    @GetMapping
    public ApiResponse getAllProducts() {
        log.info("Fetching all products");
    	List<Product> products = productService.getAllProducts();
        log.debug("Returning {} products", products.size());
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(products)
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("All products")
        								  .build();
        return response;
    }

    @GetMapping("/{id}")
    public ApiResponse getProductById(@PathVariable Long id) {
        log.info("Fetching product. productId={}", id);
    	Product product = productService.getProductById(id);
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(product)
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("Product details")
        								  .build();
        return response;
    }

//    @PostMapping //By Default the endpoint consumes and produces JSON.
    //below two ways can also be used
    
    //This is more explicit about the media types consumed and produced. It makes it clear that the endpoint specifically deals with JSON
//    @PostMapping(consumes = "application/json", produces = "application/json")
    
    //This variant allows you to specify a path for the endpoint. If you want your endpoint to have a specific path, you can use this form.
//    @PostMapping(path = "/add", consumes = "application/json", produces = "application/json")
//    public Product saveProduct(@RequestBody Product product) {
//        return productService.saveProduct(product);
//    }
    
    @PostMapping
    public  ResponseEntity<ApiResponse> saveProduct(@RequestBody Product product) {
        log.info("Creating product. name={}", product.getName());
    	Product addedProduct = productService.saveProduct(product);
        log.info("Product created successfully. productId={}, name={}", addedProduct.getId(), addedProduct.getName());
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(addedProduct)
        								  .httpStatusCode(HttpStatus.CREATED)
        								  .message("Product added successfully")
        								  .build();
    	
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
 
  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse> updateProduct(@PathVariable Long id, @Valid @RequestBody Product updatedProduct) {
      log.info("Updating product. productId={}", id);
      Product product = productService.updateProduct(id, updatedProduct);
      log.info("Product updated successfully. productId={}", id);

      ApiResponse response = ApiResponse.builder()
              							.data(product)
              							.httpStatusCode(HttpStatus.OK)
              							.message("Product updated successfully")
              							.build();

      return new ResponseEntity<>(response, HttpStatus.OK);
  }

    @DeleteMapping("/{id}")
    public ApiResponse deleteProduct(@PathVariable Long id) {
        log.info("Deleting product. productId={}", id);
  		productService.deleteProduct(id);
        log.info("Product deleted successfully. productId={}", id);
    	
        ApiResponse response = ApiResponse.builder()
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("Product deleted successfully")
        								  .build();
        return response;
    }
}
