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

public class RegisterUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	private final String snsTopicArn;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	CognitoIdentityProviderClient primaryCognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of("eu-west-1"))
			.build();

	CognitoIdentityProviderClient secondaryCognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of("eu-central-1"))
			.build();
	public RegisterUserHandler() {
		this.snsTopicArn = System.getenv("PB_LOGIN_TOPIC");
		this.appClientId = System.getenv("PB_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret = System.getenv("PB_COGNITO_POOL_SECRET_ID");
		this.cognitoUserService = new CognitoUserService(primaryCognitoClient,secondaryCognitoClient,
				new NotificationService(AwsConfig.snsClient()),"snodf6mci6tu8sqavqff4te35",
				"mbvfiscbf0fihrfafpapr4d4d211t8808lu52gmofkhdctvree5","2pcn0l10dudev2l16cqad19vn3",
				"m3f42v78ids1uqjj633ca0vc8hhj9jv5pcad54aofdh1395jhhh","eu-west-1_6bJny9B0G","eu-central-1_EvOxPU1Im");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);
		LambdaLogger logger= context.getLogger();

		try{
			JsonObject registerRequest= JsonParser.parseString(input.getBody()).getAsJsonObject();
			JsonObject registerResult=cognitoUserService.register(registerRequest,snsTopicArn);
			response.withBody(new Gson().toJson(registerResult,JsonObject.class));
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
