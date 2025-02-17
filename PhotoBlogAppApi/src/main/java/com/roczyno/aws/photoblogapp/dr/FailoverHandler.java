package com.roczyno.aws.photoblogapp.dr;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

public class FailoverHandler implements RequestHandler<SNSEvent, Void> {
	private final Route53Client route53Client = Route53Client.builder().build();
	private final SnsClient snsClient = SnsClient.builder().build();
	private LambdaLogger logger;

	private static final String HOSTED_ZONE_ID = System.getenv("HOSTED_ZONE_ID");
	private static final String NOTIFICATION_TOPIC = System.getenv("NOTIFICATION_TOPIC");
	private static final String DNS_NAME = "api.jacobphotoblogapp.click";

	@Override
	public Void handleRequest(SNSEvent event, Context context) {
		// Initialize logger
		logger = context.getLogger();
		logger.log("FailoverHandler invoked with " + event.getRecords().size() + " records");

		// Log all relevant environment variables
		logEnvironmentVariables();

		try {
			// Process the alarm event
			String snsMessage = event.getRecords().get(0).getSNS().getMessage();
			boolean isFailover = snsMessage.contains("ALARM");

			logger.log("SNS message received: " + snsMessage);
			logger.log("Failover condition detected: " + isFailover);

			if (isFailover) {
				// Update Route 53 record weights
				logger.log("Initiating failover - setting primary weight to 0, secondary to 100");
				updateDnsWeights(0, 100); // Set primary to 0, secondary to 100

				// Notify administrators
				logger.log("Sending notification to administrators");
				notifyAdministrators("Failover initiated: Traffic redirected to secondary region");

				logger.log("Failover process completed successfully");
			} else {
				logger.log("No failover action required");
			}
		} catch (Exception e) {
			logger.log("ERROR: Exception during failover handling: " + e.getMessage());
			e.printStackTrace();
			// Re-throw to ensure Lambda knows this invocation failed
			throw new RuntimeException("Failover handling failed", e);
		}

		return null;
	}

	private void logEnvironmentVariables() {
		logger.log("========== ENVIRONMENT VARIABLES ==========");
		logger.log("HOSTED_ZONE_ID: " + getSafeEnvValue("HOSTED_ZONE_ID"));
		logger.log("NOTIFICATION_TOPIC: " + getSafeEnvValue("NOTIFICATION_TOPIC"));
		logger.log("PRIMARY_API_ID: " + getSafeEnvValue("PRIMARY_API_ID"));
		logger.log("SECONDARY_API_ID: " + getSafeEnvValue("SECONDARY_API_ID"));
		logger.log("AWS_REGION: " + getSafeEnvValue("AWS_REGION"));
		logger.log("SECONDARY_REGION: " + getSafeEnvValue("SECONDARY_REGION"));
		logger.log("Static DNS_NAME: " + DNS_NAME);
		logger.log("AWS_LAMBDA_FUNCTION_NAME: " + getSafeEnvValue("AWS_LAMBDA_FUNCTION_NAME"));
		logger.log("AWS_LAMBDA_FUNCTION_VERSION: " + getSafeEnvValue("AWS_LAMBDA_FUNCTION_VERSION"));
		logger.log("AWS_LAMBDA_FUNCTION_MEMORY_SIZE: " + getSafeEnvValue("AWS_LAMBDA_FUNCTION_MEMORY_SIZE"));
		logger.log("=========================================");
	}

	private String getSafeEnvValue(String key) {
		String value = System.getenv(key);
		if (value == null || value.trim().isEmpty()) {
			return "[NOT SET]";
		}
		// For security-sensitive values, you may want to mask them
		if (key.toLowerCase().contains("key") || key.toLowerCase().contains("secret") ||
				key.toLowerCase().contains("password") || key.toLowerCase().contains("token")) {
			return "[REDACTED]";
		}
		return value;
	}

	private void updateDnsWeights(int primaryWeight, int secondaryWeight) {
		logger.log("Updating DNS weights - Primary: " + primaryWeight + ", Secondary: " + secondaryWeight);

		try {
			String primaryDns = System.getenv("PRIMARY_API_ID") + ".execute-api." +
					System.getenv("AWS_REGION") + ".amazonaws.com";
			String secondaryDns = System.getenv("SECONDARY_API_ID") + ".execute-api." +
					System.getenv("SECONDARY_REGION") + ".amazonaws.com";

			logger.log("Primary DNS: " + primaryDns);
			logger.log("Secondary DNS: " + secondaryDns);

			ChangeBatch changeBatch = ChangeBatch.builder()
					.changes(Change.builder()
									.action(ChangeAction.UPSERT)
									.resourceRecordSet(ResourceRecordSet.builder()
											.name(DNS_NAME)
											.type(RRType.A)
											.setIdentifier("primary-region")
											.weight((long) primaryWeight)
											.aliasTarget(AliasTarget.builder()
													.dnsName(primaryDns)
													.hostedZoneId("ZLY8HYME6SFDD") // API Gateway hosted zone ID
													.evaluateTargetHealth(true)
													.build())
											.build())
									.build(),
							Change.builder()
									.action(ChangeAction.UPSERT)
									.resourceRecordSet(ResourceRecordSet.builder()
											.name(DNS_NAME)
											.type(RRType.A)
											.setIdentifier("secondary-region")
											.weight((long) secondaryWeight)
											.aliasTarget(AliasTarget.builder()
													.dnsName(secondaryDns)
													.hostedZoneId("Z1U9ULNL0V5AJ3")
													.evaluateTargetHealth(true)
													.build())
											.build())
									.build())
					.build();

			ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
					.hostedZoneId(HOSTED_ZONE_ID)
					.changeBatch(changeBatch)
					.build();

			logger.log("Sending Route53 change request");
			ChangeResourceRecordSetsResponse response = route53Client.changeResourceRecordSets(request);
			logger.log("Route53 change request successful. Change info: " + response.changeInfo().toString());
		} catch (Exception e) {
			logger.log("ERROR: Failed to update DNS weights: " + e.getMessage());
			notifyAdministrators("Failed to update DNS weights: " + e.getMessage());
			throw e;
		}
	}

	private void notifyAdministrators(String message) {
		logger.log("Preparing to notify administrators: " + message);

		try {
			PublishRequest request = PublishRequest.builder()
					.topicArn(NOTIFICATION_TOPIC)
					.subject("API Failover Notification")
					.message(message)
					.build();

			PublishResponse response = snsClient.publish(request);
			logger.log("Administrator notification sent successfully. MessageId: " + response.messageId());
		} catch (Exception e) {
			// Log the error but don't throw - we don't want notification failure to affect the failover
			logger.log("ERROR: Failed to send notification: " + e.getMessage());
			System.err.println("Failed to send notification: " + e.getMessage());
		}
	}
}
