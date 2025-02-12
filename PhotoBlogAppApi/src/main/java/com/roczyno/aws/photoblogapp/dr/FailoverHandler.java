package com.roczyno.aws.photoblogapp.dr;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class FailoverHandler implements RequestHandler<SNSEvent, Void> {
	private final Route53Client route53Client = Route53Client.builder().build();
	private final SnsClient snsClient = SnsClient.builder().build();

	private static final String HOSTED_ZONE_ID = System.getenv("HOSTED_ZONE_ID");
	private static final String NOTIFICATION_TOPIC = System.getenv("NOTIFICATION_TOPIC");
	private static final String DNS_NAME = "api.photoblogapp.com";

	@Override
	public Void handleRequest(SNSEvent event, Context context) {
		// Process the alarm event
		boolean isFailover = event.getRecords().get(0).getSNS()
				.getMessage().contains("ALARM");

		if (isFailover) {
			// Update Route 53 record weights
			updateDnsWeights(0, 100); // Set primary to 0, secondary to 100

			// Notify administrators
			notifyAdministrators("Failover initiated: Traffic redirected to secondary region");
		}

		return null;
	}

	private void updateDnsWeights(int primaryWeight, int secondaryWeight) {
		try {
			ChangeBatch changeBatch = ChangeBatch.builder()
					.changes(Change.builder()
									.action(ChangeAction.UPSERT)
									.resourceRecordSet(ResourceRecordSet.builder()
											.name(DNS_NAME)
											.type(RRType.A)
											.setIdentifier("primary-region")
											.weight((long) primaryWeight)
											.aliasTarget(AliasTarget.builder()
													.dnsName(System.getenv("PRIMARY_API_ID") + ".execute-api." +
															System.getenv("AWS_REGION") + ".amazonaws.com")
													.hostedZoneId("Z1UJRXOUMOOFQ8") // API Gateway hosted zone ID
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
													.dnsName(System.getenv("SECONDARY_API_ID") + ".execute-api." +
															System.getenv("SECONDARY_REGION") + ".amazonaws.com")
													.hostedZoneId("Z1UJRXOUMOOFQ8")
													.evaluateTargetHealth(true)
													.build())
											.build())
									.build())
					.build();

			ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
					.hostedZoneId(HOSTED_ZONE_ID)
					.changeBatch(changeBatch)
					.build();

			route53Client.changeResourceRecordSets(request);
		} catch (Exception e) {
			notifyAdministrators("Failed to update DNS weights: " + e.getMessage());
			throw e;
		}
	}

	private void notifyAdministrators(String message) {
		try {
			PublishRequest request = PublishRequest.builder()
					.topicArn(NOTIFICATION_TOPIC)
					.subject("API Failover Notification")
					.message(message)
					.build();

			snsClient.publish(request);
		} catch (Exception e) {
			// Log the error but don't throw - we don't want notification failure to affect the failover
			System.err.println("Failed to send notification: " + e.getMessage());
		}
	}
}
