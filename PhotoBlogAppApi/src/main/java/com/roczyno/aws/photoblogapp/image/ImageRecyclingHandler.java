package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.GsonBuilder;
import com.roczyno.aws.photoblogapp.exceptions.ErrorResponse;
import com.roczyno.aws.photoblogapp.util.TokenUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;

import java.time.Instant;
import java.util.Map;

public class ImageRecyclingHandler {
	private final S3Client s3Client;
	private final DynamoDbClient dynamoDbClient;
	private final String primaryBucket;
	private final String imagesTable;
	private final String recycleBinPrefix = "recyclebin/";

	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public ImageRecyclingHandler() {
		this.s3Client = S3Client.builder().build();
		this.dynamoDbClient = DynamoDbClient.builder().build();
		this.primaryBucket = System.getenv("PB_PRIMARY_BUCKET");
		this.imagesTable = System.getenv("PB_IMAGES_TABLE");
	}

	public void moveToRecycleBin(String imageId, String userId) {
		try {
			// Retrieve current image details
			GetItemRequest getItemRequest = GetItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.build();

			GetItemResponse itemResponse = dynamoDbClient.getItem(getItemRequest);

			if (itemResponse.item().isEmpty()) {
				throw new RuntimeException("Image not found");
			}

			// Get current image URL
			String currentImageUrl = itemResponse.item().get("imageUrl").s();

			// Extract S3 key from URL
			String originalKey = extractS3KeyFromUrl(currentImageUrl);

			// Move image to recycle bin
			String recycledKey = recycleBinPrefix + originalKey;

			// Copy object with recycle bin metadata
			CopyObjectRequest copyRequest = CopyObjectRequest.builder()
					.sourceBucket(primaryBucket)
					.sourceKey(originalKey)
					.destinationBucket(primaryBucket)
					.destinationKey(recycledKey)
					.metadata(Map.of(
							"original-key", originalKey,
							"deleted-at", Instant.now().toString(),
							"user-id", userId
					))
					.metadataDirective(MetadataDirective.REPLACE)
					.build();

			s3Client.copyObject(copyRequest);

			// Delete original object
			DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
					.bucket(primaryBucket)
					.key(originalKey)
					.build();

			s3Client.deleteObject(deleteRequest);

			// Update DynamoDB to mark as recycled
			UpdateItemRequest updateRequest = UpdateItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.updateExpression("SET recycledImageUrl = :recycledUrl, isDeleted = :true REMOVE imageUrl")
					.expressionAttributeValues(Map.of(
							":recycledUrl", AttributeValue.builder().s(
									String.format("https://%s.s3.amazonaws.com/%s", primaryBucket, recycledKey)
							).build(),
							":true", AttributeValue.builder().bool(true).build()
					))
					.build();

			dynamoDbClient.updateItem(updateRequest);

		} catch (Exception e) {
			throw new RuntimeException("Failed to move image to recycle bin", e);
		}
	}

	public void restoreFromRecycleBin(String imageId) {
		try {
			// Retrieve recycled image details
			GetItemRequest getItemRequest = GetItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.build();

			GetItemResponse itemResponse = dynamoDbClient.getItem(getItemRequest);

			if (itemResponse.item().isEmpty() ||
					!itemResponse.item().containsKey("recycledImageUrl")) {
				throw new RuntimeException("Recycled image not found");
			}

			// Get recycled image URL
			String recycledImageUrl = itemResponse.item().get("recycledImageUrl").s();

			// Extract S3 key from recycled URL
			String recycledKey = extractS3KeyFromUrl(recycledImageUrl);

			// Retrieve original key from metadata
			HeadObjectRequest headRequest = HeadObjectRequest.builder()
					.bucket(primaryBucket)
					.key(recycledKey)
					.build();

			HeadObjectResponse headResponse = s3Client.headObject(headRequest);
			String originalKey = headResponse.metadata().get("original-key");

			// Restore image to original location
			CopyObjectRequest copyRequest = CopyObjectRequest.builder()
					.sourceBucket(primaryBucket)
					.sourceKey(recycledKey)
					.destinationBucket(primaryBucket)
					.destinationKey(originalKey)
					.metadataDirective(MetadataDirective.COPY)
					.build();

			s3Client.copyObject(copyRequest);

			// Delete recycled object
			DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
					.bucket(primaryBucket)
					.key(recycledKey)
					.build();

			s3Client.deleteObject(deleteRequest);

			// Update DynamoDB to mark as restored
			UpdateItemRequest updateRequest = UpdateItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.updateExpression("SET imageUrl = :originalUrl, isDeleted = :false REMOVE recycledImageUrl")
					.expressionAttributeValues(Map.of(
							":originalUrl", AttributeValue.builder().s(
									String.format("https://%s.s3.amazonaws.com/%s", primaryBucket, originalKey)
							).build(),
							":false", AttributeValue.builder().bool(false).build()
					))
					.build();

			dynamoDbClient.updateItem(updateRequest);

		} catch (Exception e) {
			throw new RuntimeException("Failed to restore image from recycle bin", e);
		}
	}

	public void permanentlyDeleteFromRecycleBin(String imageId) {
		try {
			// Retrieve recycled image details
			GetItemRequest getItemRequest = GetItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.build();

			GetItemResponse itemResponse = dynamoDbClient.getItem(getItemRequest);

			if (itemResponse.item().isEmpty() ||
					!itemResponse.item().containsKey("recycledImageUrl")) {
				throw new RuntimeException("Recycled image not found");
			}

			// Get recycled image URL
			String recycledImageUrl = itemResponse.item().get("recycledImageUrl").s();

			// Extract S3 key from recycled URL
			String recycledKey = extractS3KeyFromUrl(recycledImageUrl);

			// Delete from S3
			DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
					.bucket(primaryBucket)
					.key(recycledKey)
					.build();

			s3Client.deleteObject(deleteRequest);

			// Delete from DynamoDB
			DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
					.tableName(imagesTable)
					.key(Map.of(
							"imageId", AttributeValue.builder().s(imageId).build()
					))
					.build();

			dynamoDbClient.deleteItem(deleteItemRequest);

		} catch (Exception e) {
			throw new RuntimeException("Failed to permanently delete image", e);
		}
	}

	// Utility method to extract S3 key from full S3 URL
	private String extractS3KeyFromUrl(String imageUrl) {
		// Remove the bucket URL prefix
		String bucketUrlPrefix = String.format("https://%s.s3.amazonaws.com/", primaryBucket);
		return imageUrl.replace(bucketUrlPrefix, "");
	}

	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Validate authorization
			if (input.getHeaders() == null || !input.getHeaders().containsKey("Authorization")) {
				return createErrorResponse(response, 401, "Authorization header is missing");
			}

			String token = input.getHeaders().get("Authorization").replace("Bearer ", "");
			Map<String, String> userDetails = TokenUtils.extractUserDetails(token);
			String userId = userDetails.get("userId");

			// Parse input parameters
			Map<String, String> body = new GsonBuilder().create().fromJson(input.getBody(), Map.class);
			String httpMethod = input.getHttpMethod();
			String pathParam = input.getPathParameters().get("imageId");

			switch (httpMethod) {
				case "DELETE":
					if (input.getResource().contains("/recycle")) {
						moveToRecycleBin(pathParam, userId);
					} else if (input.getResource().contains("/permanent-delete")) {
						permanentlyDeleteFromRecycleBin(pathParam);
					}
					break;
				case "PUT":
					if (input.getResource().contains("/restore")) {
						restoreFromRecycleBin(pathParam);
					}
					break;
				default:
					throw new IllegalArgumentException("Unsupported HTTP method");
			}

			return response.withStatusCode(200).withBody("Operation successful");

		} catch (TokenUtils.SecurityException e) {
			logger.log("Token validation failed: " + e.getMessage());
			return createErrorResponse(response, 401, "Invalid authentication token");
		} catch (Exception e) {
			logger.log("Operation failed: " + e.getMessage());
			return createErrorResponse(response, 500, "Internal server error");
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
