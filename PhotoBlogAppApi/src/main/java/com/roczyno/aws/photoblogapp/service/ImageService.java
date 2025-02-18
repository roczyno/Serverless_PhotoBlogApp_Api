package com.roczyno.aws.photoblogapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.roczyno.aws.photoblogapp.dto.UserDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;


import java.util.Base64;
import java.util.Map;
import java.util.UUID;


public class ImageService {
	private final S3Client s3Client;
	private final SqsClient sqsClient;
	private final ObjectMapper objectMapper;

	public ImageService(S3Client s3Client, SqsClient sqsClient, ObjectMapper objectMapper) {
		this.s3Client = s3Client;
		this.sqsClient = sqsClient;
		this.objectMapper = objectMapper;
	}

	public JsonObject uploadImage(String base64Image, UserDetails userDetails, String stagingBucket, String processingQueue) {
		// Remove data:image/jpeg;base64, or similar prefixes if present
		String cleanBase64 = base64Image.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");

		try {
			byte[] imageBytes = Base64.getDecoder().decode(cleanBase64);
			String imageId = UUID.randomUUID().toString();
			String contentType = determineContentType(imageBytes);
			String extension = getExtensionFromContentType(contentType);
			String fileName = imageId + "." + extension;
			String key = String.format("%s/%s", userDetails.userId(), fileName);

			// Upload to S3
			PutObjectRequest putRequest = PutObjectRequest.builder()
					.bucket(stagingBucket)
					.key(key)
					.contentType(contentType)
					.metadata(Map.of(
							"userId", userDetails.userId(),
							"firstName", userDetails.firstName(),
							"lastName", userDetails.lastName(),
							"email", userDetails.email()
					))
					.build();

			PutObjectResponse response = s3Client.putObject(putRequest,
					RequestBody.fromBytes(imageBytes));

			// Send message to SQS
			Map<String, Object> sqsMessage = Map.of(
					"imageId", imageId,
					"userId", userDetails.userId(),
					"email",userDetails.email(),
					"fileName", fileName,
					"contentType", contentType,
					"stagingBucket", stagingBucket,
					"stagingKey", key
			);

			SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
					.queueUrl(processingQueue)
					.messageBody(objectMapper.writeValueAsString(sqsMessage))
					.build();

			sqsClient.sendMessage(sendMessageRequest);

			JsonObject uploadResponse = new JsonObject();
			uploadResponse.addProperty("imageId", imageId);
			uploadResponse.addProperty("isSuccessful", true);
			uploadResponse.addProperty("fileName", fileName);
			uploadResponse.addProperty("contentType", contentType);
			return uploadResponse;

		} catch (IllegalArgumentException e) {
			throw new RuntimeException("Invalid base64 image data", e);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to process message for SQS", e);
		}
	}

	private String determineContentType(byte[] imageBytes) {
		// Simple magic number check for common image formats
		if (imageBytes.length >= 2) {
			if (imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0xD8) {
				return "image/jpeg";
			} else if (imageBytes[0] == (byte) 0x89 && imageBytes[1] == (byte) 0x50) {
				return "image/png";
			}
		}
		return "image/jpeg"; // Default to JPEG
	}

	private String getExtensionFromContentType(String contentType) {
		return switch (contentType) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			default -> "jpg";
		};
	}
}
