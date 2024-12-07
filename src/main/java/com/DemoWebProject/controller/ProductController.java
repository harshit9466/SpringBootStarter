// src/main/java/com/example/controller/ProductController.java

package com.DemoWebProject.controller;

import com.DemoWebProject.model.Product;
import com.DemoWebProject.service.ProductService;
import com.DemoWebProject.util.ApiResponse;
import com.DemoWebProject.util.ResponseDetails;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

	private Logger logger = LoggerFactory.getLogger(ProductController.class);
	
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
    public String Home() {
        return "This is Home.";
    }
    
    @GetMapping
    public ApiResponse getAllProducts() {
    	List<Product> products = productService.getAllProducts();
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(products)
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("All products")
        								  .build();
        return response;
//      return productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ApiResponse getProductById(@PathVariable Long id) {     	
    	Product product = productService.getProductById(id);
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(product)
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("Product details")
        								  .build();
        return response;
//      return productService.getProductById(id);
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
    public  ResponseEntity<ApiResponse> saveProductWithCustomResponse(@RequestBody Product product) {
    	Product addedProduct = productService.saveProduct(product);
    	
        ApiResponse response = ApiResponse.builder()
        								  .data(addedProduct)
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("Product added successfully")
        								  .build();
    	
      //To check if the global exception handler is working use this or if you want to create a exception for a reason and send to the global exception handler instead of handling it locally here.
//    	throw new DataIntegrityViolationException("Simulated data integrity violation");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
 
  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse> updateProduct(@PathVariable Long id, @Valid @RequestBody Product updatedProduct) {
      Product product = productService.updateProduct(id, updatedProduct);

      ApiResponse response = ApiResponse.builder()
              							.data(product)
              							.httpStatusCode(HttpStatus.OK)
              							.message("Product updated successfully")
              							.build();
//      ApiResponse response = ApiResponse.success(product);

      return new ResponseEntity<>(response, HttpStatus.OK);
//      return productService.updateProduct(id, updatedProduct);
  }

    @DeleteMapping("/{id}")
    public ApiResponse deleteProduct(@PathVariable Long id) {  		
  		productService.deleteProduct(id);
    	
        ApiResponse response = ApiResponse.builder()
        								  .httpStatusCode(HttpStatus.OK)
        								  .message("Product deleted successfully")
        								  .build();
        return response;
//        productService.deleteProduct(id);
    }
}
