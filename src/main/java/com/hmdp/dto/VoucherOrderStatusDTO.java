package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherOrderStatusDTO {
    private Long orderId;
    private String publishStatus;
    private String processStatus;
    private String failureReason;
    private LocalDateTime updateTime;
}
