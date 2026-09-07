package com.hmdp.dto;

import lombok.Data;

@Data
public class VoucherOrderRecoveryRequest {
    private String requestId;
    private String reason;
}
