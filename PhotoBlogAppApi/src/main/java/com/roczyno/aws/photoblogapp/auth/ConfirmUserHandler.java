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

import java.util.Map;

public class ConfirmUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");

	public ConfirmUserHandler() {
		this.appClientId = System.getenv("PB_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret = System.getenv("PB_COGNITO_POOL_SECRET_ID");
		this.cognitoUserService = new CognitoUserService(System.getenv("AWS_REGION"),
				AwsConfig.cognitoIdentityProviderClient(),new NotificationService(AwsConfig.snsClient()));
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger= context.getLogger();
		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent();
		response.withHeaders(CORS_HEADERS);

		try{
			JsonObject confirmationRequest = JsonParser.parseString(input.getBody()).getAsJsonObject();
			String email=confirmationRequest.get("email").getAsString();
			String code=confirmationRequest.get("code").getAsString();
			JsonObject confirmSignupResponse=cognitoUserService.confirmUserSignUp(appClientId,appClientSecret,email,code);
			response.withBody(new Gson().toJson(confirmSignupResponse,JsonObject.class));
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
