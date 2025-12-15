package com.GlobeLine.stock_aggregator.exception;

public class RateLimitExceededException extends RuntimeException {
	
	public RateLimitExceededException(String message) {
		super(message);
	}
}

