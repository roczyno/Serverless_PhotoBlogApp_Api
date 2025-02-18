package com.roczyno.aws.photoblogapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsConfig {

	private static final String awsRegion = System.getenv("AWS_REGION");


	@Bean
	public static SqsClient sqsClient() {
		return SqsClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static SnsClient snsClient() {
		return SnsClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static DynamoDbClient dynamoDbClient() {
		return DynamoDbClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static SfnClient sfnClient(){
		return SfnClient.builder()
				.region(Region.of(awsRegion))
				.build();
	}
	@Bean
	public static S3Client s3Client(){
		return S3Client.builder()
				.region(Region.of(awsRegion))
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
	}

	@Bean
	public static ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}

}
