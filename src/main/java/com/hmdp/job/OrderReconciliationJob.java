package com.hmdp.job;

import com.hmdp.config.OrderReliabilityProperties;
import com.hmdp.service.OrderReliabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "hmdp.order-reliability.enabled", havingValue = "true", matchIfMissing = true)
public class OrderReconciliationJob {

    private final OrderReliabilityService orderReliabilityService;
    private final OrderReliabilityProperties properties;

    @Scheduled(fixedDelayString = "${hmdp.order-reliability.reconcile-fixed-delay-ms:30000}")
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(properties.getReconcileTimeoutSeconds());
        try {
            int reviewed = orderReliabilityService.reconcileBatch(
                    cutoff, properties.getReconcileBatchSize());
            if (reviewed > 0) {
                log.info("Voucher order reconciliation completed, reviewed={}", reviewed);
            }
        } catch (RuntimeException e) {
            log.error("Voucher order reconciliation failed; next run will retry", e);
        }
    }
}
