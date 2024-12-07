package com.DemoWebProject.util;

import org.springframework.http.HttpStatus;

public class ApiResponse {
	// Fields
	private Object data;
    private int httpStatusCode;
	private String message;

	public Object getData() {
		return data;
	}

	public void setData(Object data) {
		this.data = data;
	}

	public int  getHttpStatusCode() {
		return httpStatusCode;
	}

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	// Private constructor (can be public if needed)
	ApiResponse(Object data, int httpStatusCode, String message) {
		this.data = data;
		this.httpStatusCode = httpStatusCode;
		this.message = message;
	}

	// Static method to obtain a builder instance
	public static ApiResponseBuilder builder() {
		return new ApiResponseBuilder();
	}	

	// Success Response
	public static ApiResponse success(Object data) {
		return new ApiResponseBuilder().data(data).httpStatusCode(HttpStatus.OK).message("Success").build();
	}
	
	// Server Error Response
	public static ApiResponse serverError(Object data) {
		return new ApiResponseBuilder().httpStatusCode(HttpStatus.INTERNAL_SERVER_ERROR).message("Server Error").build();
	}

}
