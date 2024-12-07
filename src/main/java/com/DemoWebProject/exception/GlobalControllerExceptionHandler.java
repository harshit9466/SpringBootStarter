package com.DemoWebProject.exception;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@ControllerAdvice
public class GlobalControllerExceptionHandler {

//    @ResponseStatus(HttpStatus.CONFLICT)
//    @ExceptionHandler(MethodArgumentNotValidException.class)
//    public ResponseEntity<Map<String, Object>> handleConflict(MethodArgumentNotValidException ex) {
//        return buildErrorResponse(HttpStatus.CONFLICT, "Data integrity violation", ex);
//        
//        
////        Map<String, Object> response = new HashMap<>();
////        response.put("error", "Data integrity violation");
////        response.put("message", ex.getMessage());
////        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
//    }
	
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        // Handle validation errors here
        // You can extract detailed validation error information from the ex parameter
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Validation failed");
        response.put("message", "One or more fields are invalid. Check the 'errors' field for details.");
        response.put("errors", getValidationErrors(ex)); // Implement getValidationErrors method to extract error details
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
    
    private List<String> getValidationErrors(MethodArgumentNotValidException ex) {
        List<String> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            String errorMessage = error.getField() + ": " + error.getDefaultMessage();
            errors.add(errorMessage);
        }
        return errors;
    }

    // Add more @ExceptionHandler methods for other common exceptions
    // For example:
    // @ResponseStatus(HttpStatus.BAD_REQUEST)
    // @ExceptionHandler(MethodArgumentNotValidException.class)
    // public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
    //     // Handle validation errors
    // }
    
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFoundException(ProductNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "No details found", ex);
        
//        Map<String, Object> response = new HashMap<>();
//        response.put("error", "No details found");
//        response.put("message", ex.getMessage());
//        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        
//        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }
    
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFoundException(NoResourceFoundException ex) {
      return buildErrorResponse(HttpStatus.NOT_FOUND, "Endpoint not found", ex);
      
//    	Map<String, Object> response = new HashMap<>();
//        response.put("error", "Endpoint not found");
//        response.put("message", ex.getMessage());
//        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ex);
        
//        Map<String, Object> response = new HashMap<>();
//        response.put("error", "Internal server error");
//        response.put("message", ex.getMessage());
//        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
    
    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String error, Exception ex) {
        ex.printStackTrace(); // Log the exception for debugging purposes

        Map<String, Object> response = new HashMap<>();
        response.put("message", error);
        response.put("error", ex.getMessage());
        return ResponseEntity.status(status).body(response);
    }
}
