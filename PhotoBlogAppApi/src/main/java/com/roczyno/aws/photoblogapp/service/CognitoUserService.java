package com.roczyno.aws.photoblogapp.service;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class CognitoUserService {
	private final CognitoIdentityProviderClient primaryCognitoClient;
	private final CognitoIdentityProviderClient secondaryCognitoClient;
	private final NotificationService notificationService;
	private final String primaryClientId;
	private final String primaryClientSecret;
	private final String secondaryClientId;
	private final String secondaryClientSecret;
	private final String primaryUserPoolId;    // Added for admin confirmation
	private final String secondaryUserPoolId;  // Added for admin confirmation

	public CognitoUserService(
			CognitoIdentityProviderClient primaryCognitoClient,
			CognitoIdentityProviderClient secondaryCognitoClient,
			NotificationService notificationService,
			String primaryClientId,
			String primaryClientSecret,
			String secondaryClientId,
			String secondaryClientSecret,
			String primaryUserPoolId,         // New parameter
			String secondaryUserPoolId) {     // New parameter
		this.primaryCognitoClient = primaryCognitoClient;
		this.secondaryCognitoClient = secondaryCognitoClient;
		this.notificationService = notificationService;
		this.primaryClientId = primaryClientId;
		this.primaryClientSecret = primaryClientSecret;
		this.secondaryClientId = secondaryClientId;
		this.secondaryClientSecret = secondaryClientSecret;
		this.primaryUserPoolId = primaryUserPoolId;
		this.secondaryUserPoolId = secondaryUserPoolId;
	}

	public JsonObject register(JsonObject user, String snsTopicArn) {
		String email = user.get("email").getAsString();
		String password = user.get("password").getAsString();
		String firstName = user.get("firstName").getAsString();
		String lastName = user.get("lastName").getAsString();
		String userId = UUID.randomUUID().toString();

		// Create Cognito attributes
		List<AttributeType> attributes = createUserAttributes(userId, email, firstName, lastName);

		// Register in primary region
		SignUpResponse primaryResponse = registerInCognitoPool(
				email,
				password,
				attributes,
				primaryClientId,
				primaryClientSecret,
				primaryCognitoClient
		);

		// Auto-confirm in primary region
		AdminConfirmSignUpResponse primaryConfirmResponse = adminConfirmUser(
				email,
				primaryUserPoolId,
				primaryCognitoClient
		);

		// Register in secondary region
		SignUpResponse secondaryResponse = registerInCognitoPool(
				email,
				password,
				attributes,
				secondaryClientId,
				secondaryClientSecret,
				secondaryCognitoClient
		);

		// Auto-confirm in secondary region
		AdminConfirmSignUpResponse secondaryConfirmResponse = adminConfirmUser(
				email,
				secondaryUserPoolId,
				secondaryCognitoClient
		);

		// Create response
		JsonObject createUserResult = new JsonObject();
		boolean isSuccessful = primaryResponse.sdkHttpResponse().isSuccessful() &&
				secondaryResponse.sdkHttpResponse().isSuccessful() &&
				primaryConfirmResponse.sdkHttpResponse().isSuccessful() &&
				secondaryConfirmResponse.sdkHttpResponse().isSuccessful();

		createUserResult.addProperty("isSuccessful", isSuccessful);
		createUserResult.addProperty("primaryRegionStatus", primaryResponse.sdkHttpResponse().statusCode());
		createUserResult.addProperty("secondaryRegionStatus", secondaryResponse.sdkHttpResponse().statusCode());
		createUserResult.addProperty("cognitoUserId", primaryResponse.userSub());
		createUserResult.addProperty("isConfirmed", true);  // Always true now
		createUserResult.addProperty("userId", userId);

		if (isSuccessful) {
			notificationService.subscribeToLoginNotification(email, snsTopicArn);
		}

		return createUserResult;
	}

	private AdminConfirmSignUpResponse adminConfirmUser(
			String email,
			String userPoolId,
			CognitoIdentityProviderClient cognitoClient) {

		AdminConfirmSignUpRequest confirmRequest = AdminConfirmSignUpRequest.builder()
				.userPoolId(userPoolId)
				.username(email)
				.build();

		try {
			return cognitoClient.adminConfirmSignUp(confirmRequest);
		} catch (Exception e) {
			log.error("Failed to confirm user in Cognito pool", e);
			throw e;
		}
	}


	private List<AttributeType> createUserAttributes(String userId, String email, String firstName, String lastName) {
		AttributeType attributeUserId = AttributeType.builder()
				.name("custom:userId")
				.value(userId)
				.build();
		AttributeType emailAttribute = AttributeType.builder()
				.name("email")
				.value(email)
				.build();
		AttributeType nameAttribute = AttributeType.builder()
				.name("name")
				.value(firstName + " " + lastName)
				.build();

		return Arrays.asList(attributeUserId, emailAttribute, nameAttribute);
	}

	private SignUpResponse registerInCognitoPool(
			String email,
			String password,
			List<AttributeType> attributes,
			String clientId,
			String clientSecret,
			CognitoIdentityProviderClient cognitoClient) {

		String secretHash = calculateSecretHash(clientId, clientSecret, email);
		SignUpRequest signUpRequest = SignUpRequest.builder()
				.username(email)
				.password(password)
				.userAttributes(attributes)
				.clientId(clientId)
				.secretHash(secretHash)
				.build();

		try {
			return cognitoClient.signUp(signUpRequest);
		} catch (Exception e) {
			log.error("Failed to register user in Cognito pool", e);
			throw e;
		}
	}

	public JsonObject userLogin(JsonObject loginDetails, String snsTopicArn) {
		String email = loginDetails.get("email").getAsString();
		String password = loginDetails.get("password").getAsString();

		try {
			// Try primary region first
			return authenticateUser(
					email,
					password,
					primaryClientId,
					primaryClientSecret,
					primaryCognitoClient,
					snsTopicArn,
					false
			);
		} catch (Exception e) {
			log.warn("Primary region authentication failed, trying secondary region", e);

			// Try secondary region if primary fails
			return authenticateUser(
					email,
					password,
					secondaryClientId,
					secondaryClientSecret,
					secondaryCognitoClient,
					snsTopicArn,
					true
			);
		}
	}

	private JsonObject authenticateUser(
			String email,
			String password,
			String clientId,
			String clientSecret,
			CognitoIdentityProviderClient cognitoClient,
			String snsTopicArn,
			boolean isFailover) {

		String secretHash = calculateSecretHash(clientId, clientSecret, email);
		Map<String, String> authParams = new HashMap<>();
		authParams.put("USERNAME", email);
		authParams.put("PASSWORD", password);
		authParams.put("SECRET_HASH", secretHash);

		InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
				.clientId(clientId)
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.authParameters(authParams)
				.build();

		InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
		AuthenticationResultType authResult = authResponse.authenticationResult();

		// Get user details
		GetUserRequest getUserRequest = GetUserRequest.builder()
				.accessToken(authResult.accessToken())
				.build();
		GetUserResponse getUserResponse = cognitoClient.getUser(getUserRequest);

		JsonObject loginResponse = new JsonObject();
		loginResponse.addProperty("isSuccessful", authResponse.sdkHttpResponse().isSuccessful());
		loginResponse.addProperty("statusCode", authResponse.sdkHttpResponse().statusCode());
		loginResponse.addProperty("idToken", authResult.idToken());
		loginResponse.addProperty("accessToken", authResult.accessToken());
		loginResponse.addProperty("refreshToken", authResult.refreshToken());
		loginResponse.addProperty("isFailoverMode", isFailover);

		// Add user details
		JsonObject userDetails = new JsonObject();
		getUserResponse.userAttributes().forEach(attribute ->
				userDetails.addProperty(attribute.name(), attribute.value())
		);
		loginResponse.add("user", userDetails);

		if (authResponse.sdkHttpResponse().isSuccessful()) {
			notificationService.sendLoginNotification(email, snsTopicArn);
		}

		return loginResponse;
	}





	private static String calculateSecretHash(String userPoolClientId, String userPoolClientSecret, String userName) {
		final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

		SecretKeySpec signingKey = new SecretKeySpec(
				userPoolClientSecret.getBytes(StandardCharsets.UTF_8),
				HMAC_SHA256_ALGORITHM);
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
			mac.init(signingKey);
			mac.update(userName.getBytes(StandardCharsets.UTF_8));
			byte[] rawHmac = mac.doFinal(userPoolClientId.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(rawHmac);
		} catch (Exception e) {
			throw new RuntimeException("Error while calculating secret hash", e);
		}
	}
}
