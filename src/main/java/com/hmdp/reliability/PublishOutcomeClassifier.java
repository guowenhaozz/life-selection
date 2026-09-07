package com.hmdp.reliability;

public final class PublishOutcomeClassifier {

    private PublishOutcomeClassifier() {
    }

    public static OrderPublishStatus classify(boolean acknowledged, boolean returned) {
        return acknowledged && !returned
                ? OrderPublishStatus.CONFIRMED
                : OrderPublishStatus.FAILED;
    }
}
