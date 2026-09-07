package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.order-reliability")
public class OrderReliabilityProperties {

    private boolean enabled = true;
    private boolean adminEnabled = false;
    private List<Long> adminUserIds = new ArrayList<>();
    private int reconcileBatchSize = 100;
    private long reconcileFixedDelayMs = 30000L;
    private long reconcileTimeoutSeconds = 60L;
    private int deadLetterBatchSize = 100;
    private long deadLetterFixedDelayMs = 5000L;
}
