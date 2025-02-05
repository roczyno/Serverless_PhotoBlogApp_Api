package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.photoblogapp.config.AwsConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetAllImagesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final DynamoDbClient dynamoDbClient;
	private final ObjectMapper objectMapper;
	private final String imagesTable;

	public GetAllImagesHandler() {
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
			ScanRequest scanRequest = ScanRequest.builder()
					.tableName(imagesTable)
					.filterExpression("attribute_not_exists(isDeleted) OR isDeleted = :false")
					.expressionAttributeValues(Map.of(
							":false", AttributeValue.builder().bool(false).build()
					))
					.build();

			ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
			List<Map<String, String>> images = new ArrayList<>();

			for (Map<String, AttributeValue> item : scanResponse.items()) {
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
			logger.log("Error retrieving images: " + e.getMessage());
			response.setStatusCode(500);
			response.setBody("{\"error\": \"Failed to retrieve images\"}");
		}

		return response;
	}
}
