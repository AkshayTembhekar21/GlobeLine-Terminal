package com.GlobeLine.stock_aggregator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI stockAggregatorOpenAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("Stock Aggregator API")
						.description("A comprehensive API for aggregating stock market data from Finnhub. " +
								"Provides unified overview endpoints that combine company profile, real-time quotes, " +
								"financial metrics, and OHLC data into a single response.")
						.version("1.0.0")
						.contact(new Contact()
								.name("GlobeLine Terminal")
								.email("support@globeline.local"))
						.license(new License()
								.name("Proprietary")
								.url("https://globeline.local")));
	}
}

