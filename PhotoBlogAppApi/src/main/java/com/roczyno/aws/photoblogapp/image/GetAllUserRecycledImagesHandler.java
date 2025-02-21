package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.roczyno.aws.photoblogapp.util.TokenUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GetAllUserRecycledImagesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final DynamoDbClient dynamoDbClient;
	private final String imagesTable;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Access-Control-Allow-Origin", "https://main.d2enft4pt2m4ub.amplifyapp.com,http://localhost:5173",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Requested-With",
			"Access-Control-Allow-Methods", "GET,POST,OPTIONS",
			"Access-Control-Max-Age", "3600"
	);

	public GetAllUserRecycledImagesHandler() {
		this.dynamoDbClient = DynamoDbClient.builder().build();
		this.imagesTable = System.getenv("PB_IMAGES_TABLE");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		if ("OPTIONS".equals(input.getHttpMethod())) {
			return new APIGatewayProxyResponseEvent()
					.withStatusCode(200)
					.withHeaders(CORS_HEADERS);
		}

		try {
			logger.log("Extracting userId from authentication token");
			String userId = extractUserId(input);
			logger.log("Retrieved userId: " + userId);

			logger.log("Querying recycled images for user");
			List<Map<String, AttributeValue>> recycledImages = findRecycledImages(userId, logger);
			logger.log("Found " + recycledImages.size() + " recycled images");

			List<RecycledImage> formattedImages = recycledImages.stream()
					.map(this::convertToRecycledImage)
					.collect(Collectors.toList());

			logger.log("Successfully processed recycled images");
			return createSuccessResponse(formattedImages);
		} catch (TokenUtils.SecurityException e) {
			logger.log("Token validation failed: " + e.getMessage());
			return createErrorResponse(e);
		} catch (Exception e) {
			logger.log("Unexpected error retrieving recycled images: " + e.getMessage());
			return createErrorResponse(e);
		}
	}

	private List<Map<String, AttributeValue>> findRecycledImages(String userId, LambdaLogger logger) {
		logger.log("Scanning images table for recycled images of user: " + userId);
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(imagesTable)
				.filterExpression("isDeleted = :deletedFlag AND userId = :userId")
				.expressionAttributeValues(Map.of(
						":deletedFlag", AttributeValue.builder().bool(true).build(),
						":userId", AttributeValue.builder().s(userId).build()
				))
				.build();

		ScanResponse response = dynamoDbClient.scan(scanRequest);
		return response.items();
	}

	private String extractUserId(APIGatewayProxyRequestEvent input) {
		String token = input.getHeaders().get("Authorization").replace("Bearer ", "");
		Map<String, String> userDetails = TokenUtils.extractUserDetails(token);
		return userDetails.get("userId");
	}

	private RecycledImage convertToRecycledImage(Map<String, AttributeValue> item) {
		return new RecycledImage(
				item.get("imageId").s(),
				item.get("recycledImageUrl").s(),
				item.get("userId").s(),
				item.get("email").s()
		);
	}

	private APIGatewayProxyResponseEvent createSuccessResponse(List<RecycledImage> images) {
		return new APIGatewayProxyResponseEvent()
				.withStatusCode(200)
				.withHeaders(CORS_HEADERS)
				.withBody(new Gson().toJson(images));
	}

	private APIGatewayProxyResponseEvent createErrorResponse(Exception e) {
		return new APIGatewayProxyResponseEvent()
				.withStatusCode(500)
				.withHeaders(CORS_HEADERS)
				.withBody("Error: " + e.getMessage());
	}

	private static class RecycledImage {
		private String imageId;
		private String recycledImageUrl;
		private String userId;
		private String email;

		public RecycledImage(String imageId, String recycledImageUrl, String userId, String email) {
			this.imageId = imageId;
			this.recycledImageUrl = recycledImageUrl;
			this.userId = userId;
			this.email = email;
		}
	}
}
