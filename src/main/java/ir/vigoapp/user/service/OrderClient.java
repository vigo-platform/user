package ir.vigoapp.user.service;

import lombok.Getter;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.dto.order.CreateOrderRequest;
import ir.vigoapp.user.dto.order.CreateOrderResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Synchronous boundary to the Order service.
 *
 * <p>User never computes or forwards a price: the request carries identifiers and
 * the plan's declared duration only, and Order takes the authoritative price
 * snapshot. The Order service does not yet exist in this organization and no
 * contract has been agreed, so this client is disabled unless
 * {@code app.order.base-url} is configured, and the payload below is the proposed
 * contract recorded in api-contracts rather than an implementation of an existing one.
 */
@Component
public class OrderClient {
    /** Proposed request body; Order must confirm or amend before production use. */

    @Getter
    private final boolean enabled;
    private final RestClient client;

    public OrderClient(@Value("${app.order.base-url:}") String baseUrl,
                       @Value("${app.order.allow-http:false}") boolean allowHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.enabled = false;
            this.client = null;
            return;
        }
        URI base = URI.create(baseUrl);
        boolean secure = "https".equals(base.getScheme());
        if ((!secure && !(allowHttp && "http".equals(base.getScheme()))) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath())))
            throw new IllegalStateException("Order requires a trusted origin (HTTPS by default)");
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(requests).baseUrl(base.toString()).build();
        this.enabled = true;
    }

    /**
     * Creates an order. A transport failure is reported as 503 so a client can
     * retry safely; the subscription row stays PENDING_PAYMENT and is never
     * activated by this call.
     */
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        if (!enabled) throw new ApiException(503, "ORDER_UNAVAILABLE",
                "Plan purchases are not enabled", "خرید برنامه در حال حاضر فعال نیست");
        try {
            var response = client.post().uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CreateOrderResponse.class);
            if (response == null || response.order_id() == null || response.order_id() <= 0)
                throw new ApiException(503, "ORDER_UNAVAILABLE", "Order service returned no order",
                        "سرویس سفارش پاسخ معتبری برنگرداند");
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // Do not leak provider or stack details to the caller.
            throw new ApiException(503, "ORDER_UNAVAILABLE",
                    "Order service is temporarily unavailable", "سرویس سفارش موقتاً در دسترس نیست");
        }
    }
}
