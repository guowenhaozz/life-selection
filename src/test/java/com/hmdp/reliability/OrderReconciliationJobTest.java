package com.hmdp.reliability;

import com.hmdp.config.OrderReliabilityProperties;
import com.hmdp.job.OrderReconciliationJob;
import com.hmdp.service.OrderReliabilityService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderReconciliationJobTest {

    @Test
    void scheduledRunUsesConfiguredCutoffAndBatchSize() {
        OrderReliabilityService service = mock(OrderReliabilityService.class);
        OrderReliabilityProperties properties = new OrderReliabilityProperties();
        properties.setReconcileTimeoutSeconds(60L);
        properties.setReconcileBatchSize(25);
        when(service.reconcileBatch(any(LocalDateTime.class), eq(25))).thenReturn(0);

        new OrderReconciliationJob(service, properties).reconcile();

        verify(service).reconcileBatch(any(LocalDateTime.class), eq(25));
    }
}
