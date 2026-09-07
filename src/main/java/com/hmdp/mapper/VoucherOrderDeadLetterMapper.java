package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrderDeadLetter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface VoucherOrderDeadLetterMapper extends BaseMapper<VoucherOrderDeadLetter> {

    @Select("SELECT * FROM tb_voucher_order_dead_letter WHERE message_id = #{messageId} LIMIT 1")
    VoucherOrderDeadLetter selectByMessageId(@Param("messageId") String messageId);

    @Select("SELECT * FROM tb_voucher_order_dead_letter WHERE status = 'ARCHIVED' " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<VoucherOrderDeadLetter> findArchived(@Param("limit") Integer limit);

    @Update("UPDATE tb_voucher_order_dead_letter SET status = 'REPLAYED', " +
            "update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId} AND status = 'ARCHIVED'")
    int markReplayedByOrderId(@Param("orderId") Long orderId);
}
