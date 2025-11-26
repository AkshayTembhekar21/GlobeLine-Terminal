package com.GlobeLine.stock_aggregator.exception;

/**
 * Exception thrown when the service is temporarily unavailable
 * (e.g., provider API is down, rate limited, etc.)
 */
public class ServiceUnavailableException extends RuntimeException {
	public ServiceUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}

