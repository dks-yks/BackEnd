package picto.com.photostore.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import picto.com.photostore.exception.SessionSchedulerException;

@Component
@Slf4j
@RequiredArgsConstructor
public class SessionSchedulerClient {
    private final RestTemplate restTemplate;

    @Value("${session.scheduler.base-url}")
    private String baseUrl;

    @Value("${session.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    public void scheduleSession(Long photoId, Long userId, double lng, double lat) {
        // 스케줄러가 비활성화
        if (!schedulerEnabled) {
            log.warn("세션 스케줄러가 비활성화 상태입니다. 사진 ID: {}, 사용자 ID: {}에 대한 스케줄 요청을 건너뜁니다.", photoId, userId);
            return;
        }

        // 요청 URL 구성
        String url = String.format("%s/session-scheduler/shared", baseUrl);
        SessionSchedulerRequest request = new SessionSchedulerRequest(
                "SHARE",
                userId,
                photoId,
                lng,
                lat
        );

        try {
            log.info("세션 스케줄러 요청 전송: {}", request);
            ResponseEntity<Void> response = restTemplate.postForEntity(url, request, Void.class);
            log.info("세션 스케줄러 응답 상태: {}", response.getStatusCode());
        } catch (Exception e) {
            log.error("세션 스케줄링 실패", e);
            throw new SessionSchedulerException("세션 스케줄링 실패", e);
        }
    }

    @Data
    @AllArgsConstructor
    private static class SessionSchedulerRequest {
        private String messageType;
        private long senderId;
        private long photoId;
        private double lng;
        private double lat;
    }
}