package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.GsonBuilder;
import com.roczyno.aws.photoblogapp.config.AwsConfig;
import com.roczyno.aws.photoblogapp.exceptions.ErrorResponse;
import com.roczyno.aws.photoblogapp.util.TokenUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetUserImagesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final DynamoDbClient dynamoDbClient;
	private final ObjectMapper objectMapper;
	private final String imagesTable;

	public GetUserImagesHandler() {
		this.dynamoDbClient = AwsConfig.dynamoDbClient();
		this.objectMapper = AwsConfig.objectMapper();
		this.imagesTable = System.getenv("PB_IMAGES_TABLE");

		if (imagesTable == null) {
			throw new IllegalStateException("Required environment variable PB_IMAGES_TABLE must be set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

		try {
			if (input.getHeaders() == null || !input.getHeaders().containsKey("Authorization")) {
				logger.log("Authorization header missing");
				return createErrorResponse(response, 401, "Authorization header is missing");
			}

			String token = input.getHeaders().get("Authorization").replace("Bearer ", "");

			logger.log("Extracting user details from token");
			Map<String, String> userDetails;
			try {
				userDetails = TokenUtils.extractUserDetails(token);
			} catch (TokenUtils.SecurityException e) {
				logger.log("Token validation failed: " + e.getMessage());
				return createErrorResponse(response, 401, "Invalid authentication token");
			}

			String email = userDetails.get("email");

			// Query DynamoDB for user's images, excluding recycled images
			QueryRequest queryRequest = QueryRequest.builder()
					.tableName(imagesTable)
					.indexName("UserImagesIndex")
					.keyConditionExpression("email = :email")
					.filterExpression("attribute_not_exists(isDeleted) OR isDeleted = :false")
					.expressionAttributeValues(Map.of(
							":email", AttributeValue.builder().s(email).build(),
							":false", AttributeValue.builder().bool(false).build()
					))
					.build();

			QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
			List<Map<String, String>> images = new ArrayList<>();

			for (Map<String, AttributeValue> item : queryResponse.items()) {
				Map<String, String> image = new HashMap<>();
				image.put("imageId", item.get("imageId").s());
				image.put("userId", item.get("userId").s());
				image.put("firstName", item.get("firstName").s());
				image.put("lastName", item.get("lastName").s());
				image.put("imageUrl", item.get("imageUrl").s());
				image.put("uploadDate", item.get("uploadDate").s());
				images.add(image);
			}

			// Sort by uploadDate descending
			images.sort((a, b) -> b.get("uploadDate").compareTo(a.get("uploadDate")));

			String jsonBody = objectMapper.writeValueAsString(images);

			response.setStatusCode(200);
			response.setBody(jsonBody);
			response.setHeaders(Map.of(
					"Content-Type", "application/json",
					"Access-Control-Allow-Origin", "*"
			));

		} catch (Exception e) {
			logger.log("Error retrieving user images: " + e.getMessage());
			response.setStatusCode(500);
			response.setBody("{\"error\": \"Failed to retrieve user images\"}");
		}

		return response;
	}

	private APIGatewayProxyResponseEvent createErrorResponse(
			APIGatewayProxyResponseEvent response,
			int statusCode,
			String message
	) {
		ErrorResponse errorResponse = new ErrorResponse(message);
		return response
				.withStatusCode(statusCode)
				.withBody(new GsonBuilder().serializeNulls().create().toJson(errorResponse));
	}
}
