package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.roczyno.aws.photoblogapp.config.AwsConfig;
import com.roczyno.aws.photoblogapp.dto.UserDetails;
import com.roczyno.aws.photoblogapp.exceptions.ErrorResponse;
import com.roczyno.aws.photoblogapp.service.ImageService;
import com.roczyno.aws.photoblogapp.util.TokenUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.Map;


public class ImageUploadHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final String stagingBucket;
	private final String processingQueue;
	private final ImageService imageService;

	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public ImageUploadHandler() {
		this.imageService = new ImageService(AwsConfig.s3Client(), AwsConfig.sqsClient(), AwsConfig.objectMapper());
		this.stagingBucket = System.getenv("STAGING_BUCKET");
		this.processingQueue = System.getenv("PROCESSING_QUEUE");

		if (stagingBucket == null || processingQueue == null) {
			throw new IllegalStateException("Required environment variables STAGING_BUCKET and PROCESSING_QUEUE must be set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log("Processing image upload request");

		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Validate input
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

			if (input.getBody() == null) {
				logger.log("Request body is missing");
				return createErrorResponse(response, 400, "Request body is missing");
			}

			logger.log(String.format("Processing upload for user: %s", userDetails.get("userId")));
			JsonObject result = imageService.uploadImage(
					input.getBody(),
					new UserDetails(
							userDetails.get("userId"),
							userDetails.get("firstName"),
							userDetails.get("lastName"),
							userDetails.get("email")
					),
					stagingBucket,
					processingQueue
			);

			logger.log("Upload successful");
			return response
					.withStatusCode(200)
					.withBody(new Gson().toJson(result));

		} catch (AwsServiceException ex) {
			String errorMessage = String.format("AWS service error: %s. RequestId: %s",
					ex.awsErrorDetails().errorMessage(),
					context.getAwsRequestId());
			logger.log(errorMessage);
			return createErrorResponse(response, 500, ex.awsErrorDetails().errorMessage());

		} catch (Exception ex) {
			String errorMessage = String.format("Unexpected error: %s. RequestId: %s",
					ex.getMessage(),
					context.getAwsRequestId());
			logger.log(errorMessage);
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
