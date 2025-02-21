package com.roczyno.aws.photoblogapp.health;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.HashMap;
import java.util.Map;

public class HealthCheckHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put("Access-Control-Allow-Origin", "https://main.d2enft4pt2m4ub.amplifyapp.com,http://localhost:5173");

		// Simple health check response
		String responseBody = "{\"status\":\"healthy\",\"region\":\"" + System.getenv("AWS_REGION") + "\"}";

		return new APIGatewayProxyResponseEvent()
				.withStatusCode(200)
				.withHeaders(headers)
				.withBody(responseBody);
	}
}
