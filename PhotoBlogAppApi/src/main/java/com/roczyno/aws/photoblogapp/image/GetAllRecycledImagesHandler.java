package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GetAllRecycledImagesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final DynamoDbClient dynamoDbClient;
	private final String imagesTable;

	public GetAllRecycledImagesHandler() {
		this.dynamoDbClient = DynamoDbClient.builder().build();
		this.imagesTable = System.getenv("PB_IMAGES_TABLE");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		try {
			List<Map<String, AttributeValue>> recycledImages = findRecycledImages();

			List<RecycledImage> formattedImages = recycledImages.stream()
					.map(this::convertToRecycledImage)
					.collect(Collectors.toList());

			return createSuccessResponse(formattedImages);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
	}

	private List<Map<String, AttributeValue>> findRecycledImages() {
		ScanRequest scanRequest = ScanRequest.builder()
				.tableName(imagesTable)
				.filterExpression("isDeleted = :deletedFlag")
				.expressionAttributeValues(Map.of(
						":deletedFlag", AttributeValue.builder().bool(true).build()
				))
				.build();

		ScanResponse response = dynamoDbClient.scan(scanRequest);
		return response.items();
	}

	private RecycledImage convertToRecycledImage(Map<String, AttributeValue> item) {
		return new RecycledImage(
				item.get("imageId").s(),
				item.get("recycledImageUrl").s(),
				item.get("userId").s()
		);
	}

	private APIGatewayProxyResponseEvent createSuccessResponse(List<RecycledImage> images) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		response.setStatusCode(200);
		response.setBody(new Gson().toJson(images));
		return response;
	}

	private APIGatewayProxyResponseEvent createErrorResponse(Exception e) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		response.setStatusCode(500);
		response.setBody("Error: " + e.getMessage());
		return response;
	}

	// Inner class to represent recycled image
	private static class RecycledImage {
		private String imageId;
		private String recycledImageUrl;
		private String userId;

		public RecycledImage(String imageId, String recycledImageUrl, String userId) {
			this.imageId = imageId;
			this.recycledImageUrl = recycledImageUrl;
			this.userId = userId;
		}
	}
}
