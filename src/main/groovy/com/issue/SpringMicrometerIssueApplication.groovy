package com.issue

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

@SpringBootApplication
@EnableConfigurationProperties
@Slf4j
class SpringMicrometerIssueApplication {
  final Random rnd = new Random(System.currentTimeMillis())

  private static final int PCNT_CACHE_MISS = 25  //Percentage of calls that miss the authentication cache

  static void main(String[] args) {
    SpringApplication.run(SpringMicrometerIssueApplication, args)
  }

  class Unauthorized extends RuntimeException {}

  @Configuration
  class InterceptorConfig {
    final int serverPort
    final int failurePercentage

    InterceptorConfig(@Value('${server.port}') int serverPort,
        @Value('${app.fail.percent:0}') int failurePercentage) {
      this.serverPort = serverPort
      this.failurePercentage = (failurePercentage > 100 ? 100 : failurePercentage)
    }

    String uriPathTo(String endpoint) {
      "http://localhost:${serverPort}${endpoint}"
    }
  }

  @Configuration
  class ApplicationConfiguration {
    @Bean(name = "authenticatingRestTemplate")
    RestTemplate authenticatingRestTemplate(RestTemplateBuilder rtb) {
      rtb.build()
    }

    @Bean
    @Primary
    RestTemplate restTemplate(RestTemplateBuilder rtb, InterceptorConfig cfg,
        @Qualifier("authenticatingRestTemplate") RestTemplate authRT) {
      rtb.build().with { rt ->
        rt.interceptors.add(new AuthenticationInterceptor(cfg, authRT))
        rt
      }
    }
  }

  @RestController
  @Slf4j
  class SimulatedRestEndpoint {
    @Autowired private RestTemplate rt
    @Autowired private InterceptorConfig cfg

    @GetMapping(value = "/realEndpoint/{value}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.OK)
    String pretendToDoWork(@PathVariable int value, @RequestParam(required = false, defaultValue = "0") final int startAt) {
      (1..value).forEach { count ->
        log.info("Making call for {}", startAt + count)
        rt.getForObject(cfg.uriPathTo("/downstream/doWork/{value}"), Map, startAt + count)
      }

      //I'd expect all metrics to be recorded for the '/doWork/{value}'
      // but because the request is intercepted, you get the ones that 'hit'
      // the cache recorded as '/doWork/{value}', but ones that 'missed' the
      // cache recorded as '/doWork/1'...'/doWork/N', etc.
      "<html><body>Check the '/downstream/doWork' entries in <a href=\"${cfg.uriPathTo("/actuator/metrics/http.client.requests")}\">/actuator/metrics/http.client.requests</a></body></html>"
    }

    @ExceptionHandler(RestClientException)
    ResponseEntity<Map<String, Object>> handleException(RestClientException ex) {
      log.warn("Caught ${ex}", ex)
      new ResponseEntity<Map<String, Object>>(['error': ex.message], HttpStatus.INTERNAL_SERVER_ERROR)
    }
  }

  @RestController
  class SimulatedWorkCall {
    @GetMapping("/downstream/doWork/{value}")
    @ResponseBody
    @ResponseStatus(HttpStatus.OK)
    Map<String, Object> doOnwardsCall(@PathVariable String value) {
      long startTime = System.currentTimeMillis()
      final long delayUntil = startTime + (rnd.nextInt(999 - 1) + 1L) //delay between 1 ms and your specified max
      while (System.currentTimeMillis() <= delayUntil) {
        //Just cycles. This comment avoids IDE warnings
      }

      ['originalValue': value, 'duration': (System.currentTimeMillis() - startTime)]
    }

    @ExceptionHandler(Unauthorized)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void handleUnauthorized(Unauthorized ignored) {}
  }

  @RestController
  @Slf4j
  class SimulatedAuthentication {
    @Autowired
    InterceptorConfig cfg

    @PostMapping("/downstream/auth/{value}")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    Map<String, Object> simulateAuthentication(@PathVariable String value, AuthReqDetails ignored) {
      //Would not normally have auth with a path variable but I wanted to show this is recorded correctly
      if (rnd.nextInt(101) < cfg.failurePercentage) {
        throw new Unauthorized()
      }
      log.info("Simulating a successful login for value {}", value)
      ['token': 'simulatedToken']
    }

    @ExceptionHandler(Unauthorized)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void handleUnauthorized() {
      log.warn("Simulating a failed login")
    }
  }

  class AuthReqDetails {
    static final AuthReqDetails EXAMPLE = [username: 'user', passwd: 'passwd'] as AuthReqDetails

    String username
    String passwd
  }

  @Slf4j
  class AuthenticationInterceptor implements ClientHttpRequestInterceptor {
    final RestTemplate rt
    final InterceptorConfig cfg

    AuthenticationInterceptor(InterceptorConfig cfg, RestTemplate rt) {
      this.rt = rt
      this.cfg = cfg
    }

    @Override
    ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
      if (rnd.nextInt(100) < PCNT_CACHE_MISS) {
        final ResponseEntity<Map<String, Object>> authResp = makeAuthCall()

        if (authResp.statusCode != HttpStatus.OK) {
          log.warn("Auth failed - Not making call against {}", request.URI)
          throw new Unauthorized()
        }
        log.info("Auth OK - continuing call against {}", request.URI)
      }
      execution.execute(request, body)
    }

    private ResponseEntity<Map<String, Object>> makeAuthCall() {
      try {
        rt.postForEntity(cfg.uriPathTo("/downstream/auth/{value}"), AuthReqDetails.EXAMPLE, Map, rnd.nextInt(10_000))
      } catch (RestClientException ex) {
        new ResponseEntity<Map<String, Object>>(['error': ex.message], HttpStatus.INTERNAL_SERVER_ERROR)
      }
    }
  }
}
