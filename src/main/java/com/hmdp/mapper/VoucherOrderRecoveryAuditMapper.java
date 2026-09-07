package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrderRecoveryAudit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface VoucherOrderRecoveryAuditMapper extends BaseMapper<VoucherOrderRecoveryAudit> {

    @Select("SELECT * FROM tb_voucher_order_recovery_audit WHERE request_id = #{requestId}")
    VoucherOrderRecoveryAudit selectByRequestId(@Param("requestId") String requestId);

    @Update("UPDATE tb_voucher_order_recovery_audit SET result = #{result}, details = #{details}, " +
            "update_time = CURRENT_TIMESTAMP WHERE request_id = #{requestId}")
    int updateResult(@Param("requestId") String requestId,
                     @Param("result") String result,
                     @Param("details") String details);
}
