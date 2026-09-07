package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_voucher_order_dead_letter")
public class VoucherOrderDeadLetter {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String messageId;
    private Long orderId;
    private String payload;
    private String deathReason;
    private Integer retryCount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
