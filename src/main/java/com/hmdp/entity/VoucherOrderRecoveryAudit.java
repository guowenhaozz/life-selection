package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_voucher_order_recovery_audit")
public class VoucherOrderRecoveryAudit {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long orderId;
    private String action;
    private String result;
    private String reason;
    private Long operatorId;
    private String details;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
