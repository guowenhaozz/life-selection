package com.hmdp.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublishOutcomeClassifierTest {

    @Test
    void acknowledgedRoutableMessageIsConfirmed() {
        assertEquals(OrderPublishStatus.CONFIRMED,
                PublishOutcomeClassifier.classify(true, false));
    }

    @Test
    void brokerRejectionOrReturnedMessageIsFailed() {
        assertEquals(OrderPublishStatus.FAILED,
                PublishOutcomeClassifier.classify(false, false));
        assertEquals(OrderPublishStatus.FAILED,
                PublishOutcomeClassifier.classify(true, true));
    }
}
