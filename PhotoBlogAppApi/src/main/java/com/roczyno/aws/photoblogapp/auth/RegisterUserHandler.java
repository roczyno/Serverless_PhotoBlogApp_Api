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
				new NotificationService(AwsConfig.snsClient()),"49a1nhp2caq4nkeesjkp38kkdl",
				"ibce9nu63g8manjh9bfbg3sbb8cd4jlnio5mtct4f13pdk4tkh9","tojb5974i1gt0f8c1f4m238e8",
				"rb4l8mv44l447i0g5fk45qjmh0drpug1lpggjnl8hraoupandd8",
				"eu-west-1_NRAUgAWPV","eu-central-1_XhuniWl0T");
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
