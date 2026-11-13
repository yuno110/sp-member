package com.example.member.global.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * IP 키로 요청을 센다 (sp-docs/security.md §10). 경로는 등록하는 쪽({@code WebConfig})이 고르고, 이
 * 객체는 메서드를 고른다 — 규칙은 메서드와 경로로 정해진다.
 *
 * <p><b>MVC 인터셉터라 Security 의 CORS 필터를 지난 뒤, 핸들러 앞에서 센다.</b> 그래서 넘었을 때의
 * 429 에도 CORS 헤더가 실리고 브라우저가 그 응답과 {@code Retry-After} 를 읽는다 (§10). 그 경로에
 * 닿은 요청은 성공·실패를 가르지 않고 센다 — 파라미터 검증은 핸들러를 부를 때 일어나므로 형식이
 * 틀린 요청도 여기서 이미 세어졌다.
 *
 * <p><b>GET 을 고르면 HEAD 도 같은 카운터로 센다</b> — Spring MVC 는 HEAD 를 GET 핸들러로 보내므로
 * HEAD 도 그 경로에 닿은 요청이다 (§10 「세는 요청」). 세지 않으면 HEAD 로 한도를 비켜 간다.
 *
 * <p><b>preflight({@code OPTIONS})를 세지 않는다</b> (§10) — 고른 메서드(GET 이면 HEAD 까지)만 센다.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

	/** {@code :} 가 없으면 IPv4 리터럴만 받는다. 이름을 넘기면 {@link InetAddress} 가 DNS 를 찾는다. */
	private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");

	/** IPv6 는 앞 64비트로 묶는다 (§10 「키의 주소」). */
	private static final int IPV6_PREFIX_BYTES = 8;

	private final HttpMethod method;

	private final FixedWindowRateLimiter limiter;

	public RateLimitInterceptor(HttpMethod method, FixedWindowRateLimiter limiter) {
		this.method = method;
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (counts(request.getMethod())) {
			this.limiter.acquire(ipKey(request.getRemoteAddr()));
		}
		return true;
	}

	/** 고른 메서드를 센다. GET 을 골랐으면 HEAD 도 센다 — GET 핸들러가 HEAD 를 받는다. */
	private boolean counts(String requestMethod) {
		return this.method.matches(requestMethod)
				|| (HttpMethod.GET.equals(this.method) && HttpMethod.HEAD.matches(requestMethod));
	}

	/**
	 * 요청의 IP 키. <b>{@code request.getRemoteAddr()} 만 쓴다</b> — {@code X-Forwarded-For}·
	 * {@code X-Real-IP} 는 클라이언트가 임의로 쓰는 헤더라 믿으면 요청마다 주소를 바꿔 제한을 비켜
	 * 간다. 컨테이너도 그 헤더로 주소를 바꾸지 않는다({@code server.forward-headers-strategy: none},
	 * sp-docs/tech-stack.md §4.6).
	 *
	 * <ul>
	 *   <li>IPv4 는 주소 그대로다</li>
	 *   <li>IPv6 는 앞 64비트(/64)로 묶는다 — 주소 하나를 키로 하면 /64 를 받은 호스트 하나가
	 *       주소를 바꿔 가며 키를 만든다</li>
	 *   <li>IPv4 사상 주소({@code ::ffff:a.b.c.d})는 IPv4 로 본다 — {@link InetAddress} 가 그 표기를
	 *       {@link Inet4Address} 로 돌려준다({@code Inet6Address} 의 javadoc)</li>
	 * </ul>
	 *
	 * @throws IllegalStateException 주소가 IP 리터럴이 아니다. 서블릿 컨테이너는 {@code remoteAddr}
	 *         에 IP 를 주므로 생기지 않을 일이고, 생기면 키를 지어내지 않고 드러낸다
	 */
	static String ipKey(String remoteAddr) {
		if (remoteAddr == null || (remoteAddr.indexOf(':') < 0 && !IPV4_LITERAL.matcher(remoteAddr).matches())) {
			throw new IllegalStateException("remoteAddr 가 IP 리터럴이 아니다");
		}
		InetAddress address;
		try {
			address = InetAddress.getByName(remoteAddr);
		}
		catch (UnknownHostException e) {
			throw new IllegalStateException("remoteAddr 가 IP 리터럴이 아니다", e);
		}
		if (address instanceof Inet4Address) {
			return address.getHostAddress();
		}
		return HexFormat.of().formatHex(address.getAddress(), 0, IPV6_PREFIX_BYTES) + "/64";
	}

}
