package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_voucher_order_process")
public class VoucherOrderProcess {

    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String publishStatus;
    private String processStatus;
    private String failureReason;
    private Integer retryCount;
    private LocalDateTime reservedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
