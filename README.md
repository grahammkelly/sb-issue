# sb-issue

Repo for code describing issue in spring-boot w. micrometer

As of Spring boot 2.2.2 (and 2.1.9), there is an issue in the micrometer integration when you have a `RestTemplate` call being intercepted and redirected to another URL.

## Use case

The main use case I have, being affected by the issue, is where I intercept a call to an external API to provide authentication on the call.

For instance, we call an external API, but intercept to check if already authenticated. If authenticated, add the auth token to the call. If NOT authenticated, place a call to another endpoint to perform the authentication, then add the auth token to the original call and complete the original call.

In the case where the auth call is being made, the metrics details for the original call is being lost. TBF, this mainly affects calls with path parameters.

This seems to be an issue in how the `org.springframework.boot.actuate.metrics.web.client.MetricsClientHttpRequestInterceptor` stores the `urlTemplate`, as a `ThreadLocal`. In the example supplied, the `intercept` method will wipe the `urlTemplate` after completion of the auth call.

```java
    @Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		...
		try {
			response = execution.execute(request, body);
			return response;
		}
		finally {
			getTimeBuilder(request, response).register(this.meterRegistry).record(System.nanoTime() - startTime,
					TimeUnit.NANOSECONDS);
			urlTemplate.remove();   //This is the offending command
		}
	}
```

Ideally, the urlTemplate needs to be a threadlocal stack of some type, OR if the performance hit on this is too large, there needs to be some way to override the default functionality for anyone needing this sort of functionality.
