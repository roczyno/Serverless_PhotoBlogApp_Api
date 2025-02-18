package com.roczyno.aws.photoblogapp.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.photoblogapp.config.AwsConfig;
import com.roczyno.aws.photoblogapp.exceptions.ErrorResponse;
import com.roczyno.aws.photoblogapp.service.CognitoUserService;
import com.roczyno.aws.photoblogapp.service.NotificationService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import java.util.Map;

public class LoginUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	private final String snsTopicArn;

	CognitoIdentityProviderClient primaryCognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of("eu-west-1"))
			.build();

	CognitoIdentityProviderClient secondaryCognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of("eu-central-1"))
			.build();

	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public LoginUserHandler() {
		this.snsTopicArn = System.getenv("PB_LOGIN_TOPIC");
		this.appClientId = System.getenv("PB_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret = System.getenv("PB_COGNITO_POOL_SECRET_ID");
		this.cognitoUserService = new CognitoUserService(primaryCognitoClient,secondaryCognitoClient,
				new NotificationService(AwsConfig.snsClient()),"5mqq5bsebbn7v689p25uv5uj2q",
				"1jc35tu45vhvmkgb5ou5k6rtm7ah4tjonp2ncv2hk0r40aqflqcp","7p669rvvrg45m211crqf3dhkmu",
				"4gs6f558471lrm31ipgjlmum4ooef1g33ml6gl75inj73q9ed1v","eu-west-1_KkSbd6kX8","eu-central-1_HaIIjUvcL");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {

		LambdaLogger logger= context.getLogger();
		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent();
		response.withHeaders(CORS_HEADERS);

		try{
			JsonObject loginUserRequest= JsonParser.parseString(input.getBody()).getAsJsonObject();
			JsonObject loginResult=cognitoUserService.userLogin(loginUserRequest,snsTopicArn);
			response.withBody(new Gson().toJson(loginResult,JsonObject.class));
			response.withStatusCode(200);

		}catch (AwsServiceException ex){
			logger.log(ex.awsErrorDetails().errorMessage());
			ErrorResponse errorResponse= new ErrorResponse(ex.awsErrorDetails().errorMessage());
			String errorResponseJsonString=new GsonBuilder().serializeNulls().create().toJson(errorResponse,ErrorResponse.class);
			response.withBody(errorResponseJsonString);
			response.withStatusCode(500);

		}catch (Exception ex){
			logger.log(ex.getMessage());
			ErrorResponse errorResponse= new ErrorResponse(ex.getMessage());
			String errorResponseJsonString=new GsonBuilder().serializeNulls().create().toJson(errorResponse,ErrorResponse.class);
			response.withBody(errorResponseJsonString);
			response.withStatusCode(500);
		}
		return response;
	}
}
