package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;

public interface VoucherOrderPublisher {
    OrderPublishStatus publish(VoucherOrder order);
}
