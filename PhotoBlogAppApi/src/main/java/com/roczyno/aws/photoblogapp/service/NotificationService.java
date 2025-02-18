package com.roczyno.aws.photoblogapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NotificationService {
	private final SnsClient snsClient;

	public NotificationService(SnsClient snsClient) {
		this.snsClient = snsClient;
	}


	public void subscribeToLoginNotification(String email, String snsTopicArn) {
		try {
			// Create a filter policy with the correct structure
			Map<String, List<String>> filterPolicy = new HashMap<>();
			filterPolicy.put("email", Collections.singletonList(email));

			// Create the subscription request
			SubscribeRequest request = SubscribeRequest.builder()
					.protocol("email")
					.endpoint(email)
					.returnSubscriptionArn(true)
					.attributes(Map.of(
							"FilterPolicy", new ObjectMapper()
									.writeValueAsString(filterPolicy)
					))
					.topicArn(snsTopicArn)
					.build();

			snsClient.subscribe(request);
			log.info("Successfully subscribed user {} to notifications", email);

		} catch (Exception e) {
			log.error("Failed to subscribe user to notifications: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to subscribe to notifications", e);
		}
	}

	public void sendLoginNotification(String email,String snsTopicArn){
		String message = String.format("New login detected for user: %s at %s",
				email,
				java.time.LocalDateTime.now());

		Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();


		messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
				.dataType("String")
				.stringValue(email)
				.build());
		PublishRequest request = PublishRequest.builder()
				.message(message)
				.messageAttributes(messageAttributes)
				.topicArn(snsTopicArn)
				.build();

		snsClient.publish(request);
	}
}
