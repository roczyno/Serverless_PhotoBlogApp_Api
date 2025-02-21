package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.roczyno.aws.photoblogapp.exceptions.ErrorResponse;
import com.roczyno.aws.photoblogapp.util.TokenUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Duration;
import java.util.Map;

public class ShareImageHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final DynamoDbClient dynamoDbClient;
	private final S3Presigner presigner;
	private final String imagesTable;
	private final String primaryBucket;
	private static final Duration EXPIRATION = Duration.ofHours(3);
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "https://main.d2enft4pt2m4ub.amplifyapp.com,http://localhost:5173",
			"Access-Control-Allow-Methods", "GET",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public ShareImageHandler() {
		this.dynamoDbClient = DynamoDbClient.builder().build();
		this.presigner = S3Presigner.builder().build();
		this.imagesTable = System.getenv("IMAGES_TABLE");
		this.primaryBucket = System.getenv("PRIMARY_BUCKET");

		if (imagesTable == null || primaryBucket == null) {
			throw new IllegalStateException("Required environment variables IMAGES_TABLE and PRIMARY_BUCKET must be set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log("Processing share image request");

		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Validate token and extract user details
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

			// Get imageId from path parameter
			Map<String, String> pathParameters = input.getPathParameters();
			if (pathParameters == null || !pathParameters.containsKey("imageId")) {
				return createErrorResponse(response, 400, "Image ID is required");
			}
			String imageId = pathParameters.get("imageId");
			String userId = userDetails.get("userId");

			// Verify image exists and belongs to user
			GetItemResponse item = dynamoDbClient.getItem(GetItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of("imageId", AttributeValue.builder().s(imageId).build()))
					.build());

			if (item.item() == null || !item.item().get("userId").s().equals(userId)) {
				return createErrorResponse(response, 404, "Image not found or access denied");
			}
			// Get the image URL from DynamoDB
			String imageUrl = item.item().get("imageUrl").s();
			logger.log("Retrieved image URL from DynamoDB: " + imageUrl);


			// Extract the S3 key from the URL
			// Remove the S3 bucket URL prefix to get just the key
			String bucketUrlPrefix = String.format("https://%s.s3.amazonaws.com/", primaryBucket);
			String key = imageUrl.substring(bucketUrlPrefix.length());

			logger.log("Generated S3 key for presigned URL: " + key);

			GetObjectRequest getObjectRequest = GetObjectRequest.builder()
					.bucket(primaryBucket)
					.key(key)
					.build();

			GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
					.signatureDuration(EXPIRATION)
					.getObjectRequest(getObjectRequest)
					.build();

			String presignedUrl = presigner.presignGetObject(presignRequest).url().toString();
			logger.log("Generated presigned URL (masked): " + presignedUrl.substring(0, 50) + "...");

			// Create response
			JsonObject result = new JsonObject();
			result.addProperty("presignedUrl", presignedUrl);
			result.addProperty("expiresIn", EXPIRATION.toSeconds());

			return response
					.withStatusCode(200)
					.withBody(new Gson().toJson(result));

		} catch (Exception e) {
			logger.log("Error generating presigned URL: " + e.getMessage());
			return createErrorResponse(response, 500, "Error generating share link");
		}
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
