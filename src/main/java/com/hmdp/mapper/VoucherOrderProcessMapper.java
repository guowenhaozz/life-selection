package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrderProcess;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VoucherOrderProcessMapper extends BaseMapper<VoucherOrderProcess> {

    @Select("SELECT * FROM tb_voucher_order_process " +
            "WHERE process_status = 'PENDING' AND update_time < #{cutoff} " +
            "ORDER BY update_time ASC LIMIT #{limit}")
    List<VoucherOrderProcess> findUnresolvedBefore(@Param("cutoff") LocalDateTime cutoff,
                                                    @Param("limit") Integer limit);

    @Select("SELECT * FROM tb_voucher_order_process " +
            "WHERE process_status IN ('MANUAL_REVIEW', 'CANCEL_PENDING_REFUND') " +
            "ORDER BY update_time DESC LIMIT #{limit}")
    List<VoucherOrderProcess> findAnomalies(@Param("limit") Integer limit);

    @Update("UPDATE tb_voucher_order_process SET process_status = 'MANUAL_REVIEW', " +
            "failure_reason = #{reason}, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_id = #{orderId} AND process_status = 'PENDING'")
    int markManualReview(@Param("orderId") Long orderId, @Param("reason") String reason);

    @Update("UPDATE tb_voucher_order_process SET process_status = 'PENDING', " +
            "failure_reason = NULL, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_id = #{orderId} AND process_status IN ('PENDING', 'MANUAL_REVIEW')")
    int markReplayPending(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order_process SET process_status = 'CANCEL_PENDING_REFUND', " +
            "failure_reason = NULL, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_id = #{orderId} AND process_status IN ('PENDING', 'MANUAL_REVIEW')")
    int markCancelPending(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order_process SET process_status = 'REFUNDED', " +
            "failure_reason = NULL, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_id = #{orderId} AND process_status = 'CANCEL_PENDING_REFUND'")
    int markRefunded(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order_process SET publish_status = #{status}, " +
            "failure_reason = CASE WHEN process_status IN ('PENDING', 'MANUAL_REVIEW') " +
            "THEN #{reason} ELSE failure_reason END, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_id = #{orderId}")
    int updatePublishStatus(@Param("orderId") Long orderId,
                            @Param("status") String status,
                            @Param("reason") String reason);

    @Update("UPDATE tb_voucher_order_process SET process_status = 'PERSISTED', " +
            "failure_reason = NULL, update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId} " +
            "AND process_status IN ('PENDING', 'MANUAL_REVIEW')")
    int markPersisted(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order_process SET retry_count = retry_count + 1, " +
            "update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId}")
    int incrementRetryCount(@Param("orderId") Long orderId);

    @Select("SELECT * FROM tb_voucher_order_process WHERE order_id = #{orderId} FOR UPDATE")
    VoucherOrderProcess selectForUpdate(@Param("orderId") Long orderId);
}
