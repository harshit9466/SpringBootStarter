package com.DemoWebProject.util;

import org.springframework.http.HttpStatus;

public class ApiResponseBuilder {
    private Object data;
    private int httpStatusCode;
    private String message;
    
    // Chainable setter methods
    public ApiResponseBuilder data(Object data) {
        this.data = data;
        return this;
    }

    public ApiResponseBuilder httpStatusCode(int httpStatusCode) { // Overload for custom status codes
        this.httpStatusCode = httpStatusCode;
        return this;
    }

    public ApiResponseBuilder httpStatusCode(HttpStatus httpStatusCode) { // Overload for standard HttpStatus
        this.httpStatusCode = httpStatusCode.value();
        return this;
    }


    public ApiResponseBuilder message(String message) {
        this.message = message;
        return this;
    }

    // Build method to create an instance of ApiResponse
    public ApiResponse build() {
        return new ApiResponse(data, httpStatusCode, message);
    }
}
