package com.roczyno.aws.photoblogapp.service;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class CognitoUserService {
	private final CognitoIdentityProviderClient cognitoIdentityProviderClient;

	private final NotificationService notificationService;

	public CognitoUserService(String region, CognitoIdentityProviderClient cognitoIdentityProviderClient, NotificationService notificationService) {
		this.cognitoIdentityProviderClient = cognitoIdentityProviderClient;
		this.notificationService = notificationService;
	}

	public JsonObject register(JsonObject user,String appClientId,String appClientSecret,String snsTopicArn){

		String email=user.get("email").getAsString();
		String password=user.get("password").getAsString();
		String firstName=user.get("firstName").getAsString();
		String lastName=user.get("lastName").getAsString();
		String userId= UUID.randomUUID().toString();

		AttributeType attributeUserId=AttributeType.builder()
				.name("custom:userId")
				.value(userId)
				.build();
		AttributeType emailAttribute=AttributeType.builder()
				.name("email")
				.value(email)
				.build();
		AttributeType nameAttribute=AttributeType.builder()
				.name("name")
				.value(firstName+" "+lastName)
				.build();

		List<AttributeType> attributes=new ArrayList<>();
		attributes.add(nameAttribute);
		attributes.add(emailAttribute);
		attributes.add(attributeUserId);



		String generateSecretHash=calculateSecretHash(appClientId,appClientSecret,email);
		SignUpRequest signUpRequest= SignUpRequest.builder()
				.username(email)
				.password(password)
				.userAttributes(attributes)
				.clientId(appClientId)
				.secretHash(generateSecretHash)
				.build();

		SignUpResponse signUpResponse=cognitoIdentityProviderClient.signUp(signUpRequest);
		JsonObject createUserResult= new JsonObject();
		createUserResult.addProperty("isSuccessful",signUpResponse.sdkHttpResponse().isSuccessful());
		createUserResult.addProperty("statusCode",signUpResponse.sdkHttpResponse().statusCode());
		createUserResult.addProperty("cognitoUserId",signUpResponse.userSub());
		createUserResult.addProperty("isConfirmed",signUpResponse.userConfirmed());

		if(signUpResponse.sdkHttpResponse().isSuccessful()){
			notificationService.subscribeToLoginNotification(email,snsTopicArn);
		}

		return createUserResult;
	}




	public JsonObject userLogin(JsonObject loginDetails, String appClientId, String appClientSecret,String snsTopicArn) {
		String email = loginDetails.get("email").getAsString();
		String generateSecretHash = calculateSecretHash(appClientId, appClientSecret, email);
		String password = loginDetails.get("password").getAsString();
		Map<String, String> authParams = new HashMap<>() {
			{
				put("USERNAME", email);
				put("PASSWORD", password);
				put("SECRET_HASH", generateSecretHash);
			}
		};
		InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
				.clientId(appClientId)
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.authParameters(authParams)
				.build();

		InitiateAuthResponse initiateAuthResponse = cognitoIdentityProviderClient.initiateAuth(authRequest);
		AuthenticationResultType authenticationResultType = initiateAuthResponse.authenticationResult();
		JsonObject loginUserResponse = new JsonObject();
		loginUserResponse.addProperty("isSuccessful", initiateAuthResponse.sdkHttpResponse().isSuccessful());
		loginUserResponse.addProperty("statusCode", initiateAuthResponse.sdkHttpResponse().statusCode());
		loginUserResponse.addProperty("idToken", authenticationResultType.idToken());
		loginUserResponse.addProperty("accessToken", authenticationResultType.accessToken());
		loginUserResponse.addProperty("refreshToken", authenticationResultType.refreshToken());

		// Get user details using the access token
		GetUserRequest getUserRequest = GetUserRequest.builder()
				.accessToken(authenticationResultType.accessToken())
				.build();
		GetUserResponse getUserResponse = cognitoIdentityProviderClient.getUser(getUserRequest);

		// Create user details object
		JsonObject userDetails = new JsonObject();
		getUserResponse.userAttributes().forEach(attribute -> {
			userDetails.addProperty(attribute.name(), attribute.value());
		});

		// Add user details to response
		loginUserResponse.add("user", userDetails);


		if(initiateAuthResponse.sdkHttpResponse().isSuccessful()){
			notificationService.sendLoginNotification(email,snsTopicArn);
		}

		return loginUserResponse;
	}

	public static String calculateSecretHash(String userPoolClientId, String userPoolClientSecret, String userName) {
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
			throw new RuntimeException("Error while calculating ");
		}
	}

	public JsonObject confirmUserSignUp(String appClientId,String appClientSecret,String email,String confirmationCode){
		String generateSecretHash=calculateSecretHash(appClientId,appClientSecret,email);
		ConfirmSignUpRequest confirmSignUpRequest=ConfirmSignUpRequest.builder()
				.secretHash(generateSecretHash)
				.username(email)
				.confirmationCode(confirmationCode)
				.clientId(appClientId)
				.build();

		ConfirmSignUpResponse confirmSignUpResponse=cognitoIdentityProviderClient.confirmSignUp(confirmSignUpRequest);
		JsonObject confirmUserResponse=new JsonObject();
		confirmUserResponse.addProperty("isSuccessful",confirmSignUpResponse.sdkHttpResponse().isSuccessful());
		confirmUserResponse.addProperty("statusCode",confirmSignUpResponse.sdkHttpResponse().statusCode());
		return confirmUserResponse;
	}

}
