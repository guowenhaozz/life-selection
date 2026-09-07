package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrderDeadLetter;
import com.hmdp.mapper.VoucherOrderDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderProcessMapper;
import com.hmdp.service.DeadLetterArchiveService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterArchiveServiceTest {

    @Mock
    private VoucherOrderDeadLetterMapper deadLetterMapper;
    @Mock
    private VoucherOrderProcessMapper processMapper;
    @Mock
    private RedisReservationTracker reservationTracker;

    private DeadLetterArchiveService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterArchiveService(deadLetterMapper, processMapper, reservationTracker);
    }

    @Test
    void archivesVoucherOrderAndRecordsDeathMetadata() {
        Map<String, Object> death = new HashMap<>();
        death.put("reason", "rejected");
        death.put("count", 3L);
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-death", Collections.singletonList(death));
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId("message-1")
                .headers(headers)
                .build();
        GetResponse response = response("{\"id\":101,\"userId\":7,\"voucherId\":2}", properties);
        when(deadLetterMapper.selectByMessageId("message-1")).thenReturn(null);
        when(deadLetterMapper.insert(any(VoucherOrderDeadLetter.class))).thenReturn(1);

        service.archive(response);

        ArgumentCaptor<VoucherOrderDeadLetter> captor = ArgumentCaptor.forClass(VoucherOrderDeadLetter.class);
        verify(deadLetterMapper).insert(captor.capture());
        verify(processMapper).markManualReview(101L, "DEAD_LETTER_rejected");
        assertEquals(101L, captor.getValue().getOrderId());
        assertEquals("rejected", captor.getValue().getDeathReason());
        assertEquals(3, captor.getValue().getRetryCount());
    }

    @Test
    void malformedPayloadIsStillArchivedWithoutBlockingTheBatch() {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId("message-2")
                .build();
        when(deadLetterMapper.selectByMessageId("message-2")).thenReturn(null);
        when(deadLetterMapper.insert(any(VoucherOrderDeadLetter.class))).thenReturn(1);

        service.archive(response("not-json", properties));

        ArgumentCaptor<VoucherOrderDeadLetter> captor = ArgumentCaptor.forClass(VoucherOrderDeadLetter.class);
        verify(deadLetterMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getOrderId());
        assertEquals("UNKNOWN", captor.getValue().getDeathReason());
    }

    private GetResponse response(String payload, AMQP.BasicProperties properties) {
        Envelope envelope = new Envelope(1L, false, "exchange", "routing-key");
        return new GetResponse(envelope, properties,
                payload.getBytes(StandardCharsets.UTF_8), 0);
    }
}
