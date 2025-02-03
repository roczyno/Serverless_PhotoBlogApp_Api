package com.roczyno.aws.photoblogapp.image;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.photoblogapp.config.AwsConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ImageProcessorHandler implements RequestHandler<SQSEvent, Void> {
	private final S3Client s3Client;
	private final DynamoDbClient dynamoDbClient;
	private final ObjectMapper objectMapper;
	private final String primaryBucket;
	private final String imagesTable;
	private final SnsClient snsClient;
	private final String imageProcessingFailedSnsTopic;

	public ImageProcessorHandler() {
		this.imageProcessingFailedSnsTopic = System.getenv("PB_FAILURE_NOTIFICATION_TOPIC");
		this.snsClient = AwsConfig.snsClient();
		this.s3Client = AwsConfig.s3Client();
		this.dynamoDbClient = AwsConfig.dynamoDbClient();
		this.objectMapper = AwsConfig.objectMapper();
		this.primaryBucket = System.getenv("PB_PRIMARY_BUCKET");
		this.imagesTable = System.getenv("PB_IMAGES_TABLE");

		if (primaryBucket == null || imagesTable == null) {
			throw new IllegalStateException("Required environment variables PRIMARY_BUCKET and IMAGES_TABLE must be set");
		}
	}

	@Override
	public Void handleRequest(SQSEvent event, Context context) {
		LambdaLogger logger = context.getLogger();

		for (SQSEvent.SQSMessage message : event.getRecords()) {
			try {
				// Parse the message
				Map<String, String> messageBody = objectMapper.readValue(message.getBody(), Map.class);
				String stagingBucket = messageBody.get("stagingBucket");
				String stagingKey = messageBody.get("stagingKey");
				String userId = messageBody.get("userId");
				String imageId = messageBody.get("imageId");
				String contentType = messageBody.get("contentType");

				// Download image from staging bucket
				GetObjectRequest getObjectRequest = GetObjectRequest.builder()
						.bucket(stagingBucket)
						.key(stagingKey)
						.build();

				// Get image metadata
				Map<String, String> metadata = s3Client.getObject(getObjectRequest)
						.response()
						.metadata();

				String firstName = metadata.get("firstname");
				String lastName = metadata.get("lastname");
				String fullName = firstName + " " + lastName;

				// Process the image
				InputStream imageInputStream = s3Client.getObject(getObjectRequest);
				BufferedImage originalImage = ImageIO.read(imageInputStream);

				// Add watermark
				BufferedImage watermarkedImage = addWatermark(originalImage, fullName);

				// Convert back to bytes
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				String format = contentType.contains("png") ? "PNG" : "JPEG";
				ImageIO.write(watermarkedImage, format, outputStream);
				byte[] processedImageBytes = outputStream.toByteArray();

				// Upload to primary bucket
				String primaryKey = String.format("%s/%s", userId, messageBody.get("fileName"));
				PutObjectRequest putRequest = PutObjectRequest.builder()
						.bucket(primaryBucket)
						.key(primaryKey)
						.contentType(contentType)
						.metadata(metadata)
						.build();

				s3Client.putObject(putRequest, RequestBody.fromBytes(processedImageBytes));

				// Generate S3 URL
				String imageUrl = String.format("https://%s.s3.amazonaws.com/%s", primaryBucket, primaryKey);

				// Store in DynamoDB
				Map<String, AttributeValue> item = new HashMap<>();
				item.put("imageId", AttributeValue.builder().s(imageId).build());
				item.put("userId", AttributeValue.builder().s(userId).build());
				item.put("firstName", AttributeValue.builder().s(firstName).build());
				item.put("lastName", AttributeValue.builder().s(lastName).build());
				item.put("imageUrl", AttributeValue.builder().s(imageUrl).build());
				item.put("uploadDate", AttributeValue.builder().s(
						LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
				).build());

				PutItemRequest putItemRequest = PutItemRequest.builder()
						.tableName(imagesTable)
						.item(item)
						.build();

				dynamoDbClient.putItem(putItemRequest);

				logger.log(String.format("Successfully processed image %s for user %s", imageId, userId));

				try {
					DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
							.bucket(stagingBucket)
							.key(stagingKey)
							.build();

					logger.log(String.format("Attempting to delete object from bucket %s with key %s",
							stagingBucket, stagingKey));

					s3Client.deleteObject(deleteRequest);

					logger.log(String.format("Successfully deleted object from bucket %s with key %s",
							stagingBucket, stagingKey));
				} catch (Exception e) {
					logger.log(String.format("Failed to delete object from bucket %s with key %s. Error: %s",
							stagingBucket, stagingKey, e.getMessage()));
				}


			} catch (Exception e) {
				logger.log(String.format("Error processing message: %s. Error: %s", message.getBody(), e.getMessage()));
				sendFailureNotification(message.getBody(), e.getMessage());
				throw new RuntimeException("Failed to process image", e);
			}
		}
		return null;
	}

	private BufferedImage addWatermark(BufferedImage image, String watermarkText) {
		// Create a copy of the original image
		BufferedImage watermarked = new BufferedImage(
				image.getWidth(),
				image.getHeight(),
				BufferedImage.TYPE_INT_RGB);

		// Get graphics context
		Graphics2D g2d = watermarked.createGraphics();

		// Draw the original image
		g2d.drawImage(image, 0, 0, null);

		// Set up the watermark
		g2d.setColor(new Color(255, 255, 255, 128)); // Semi-transparent white
		g2d.setFont(new Font("Arial", Font.BOLD, 36));

		// Add current date to watermark
		String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		String fullWatermark = watermarkText + " • " + dateStr;

		// Calculate position (bottom right corner)
		FontMetrics fontMetrics = g2d.getFontMetrics();
		int watermarkX = image.getWidth() - fontMetrics.stringWidth(fullWatermark) - 20;
		int watermarkY = image.getHeight() - fontMetrics.getHeight() - 20;

		// Draw watermark text
		g2d.drawString(fullWatermark, watermarkX, watermarkY);

		g2d.dispose();
		return watermarked;
	}

	private void sendFailureNotification(String originalMessage, String errorMessage) {
		try {
			// Create JSON-like message using Jackson ObjectMapper
			String notificationMessage = objectMapper.writeValueAsString(Map.of(
					"originalMessage", originalMessage,
					"errorMessage", errorMessage,
					"timestamp", LocalDateTime.now().toString()
			));

			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(imageProcessingFailedSnsTopic)
					.message(notificationMessage)
					.messageGroupId("image-processing-failures")
					.build();

			snsClient.publish(publishRequest);
		} catch (Exception e) {
			System.err.println("Failed to send SNS notification: " + e.getMessage());
		}
	}
}
