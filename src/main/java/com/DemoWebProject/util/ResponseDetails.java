package com.DemoWebProject.util;

import org.springframework.http.HttpStatus;

public class ResponseDetails {

	public static final int INTERNAL_SERVER_ERROR_CUSTOM_HttpStatusCode = 5000;
	
	public static final HttpStatus INTERNAL_SERVER_ERROR_HttpStatusCode = HttpStatus.INTERNAL_SERVER_ERROR;
	public static final String INTERNAL_SERVER_ERROR_ResponseMessage = "Server Error";
	
	public static final int BAD_REQUEST_HttpStatusCode = 400;
	public static final String BAD_REQUEST_ResponseMessage = "BAD_REQUEST";

	public static class Specialization {
		public static final int OK_HttpStatusCode = 200;
		public static final String OK_ResponseMessage = "Product updated successfully";
		public static final int NOT_FOUND_HttpStatusCode = 404;
		public static final String NOT_FOUND_ResponseMessage = "Error while adding details";
	}
}
