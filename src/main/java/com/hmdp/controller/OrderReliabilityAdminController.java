package com.hmdp.controller;

import com.hmdp.config.OrderReliabilityProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderRecoveryRequest;
import com.hmdp.service.OrderReliabilityService;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/order-reliability")
@RequiredArgsConstructor
public class OrderReliabilityAdminController {

    private final OrderReliabilityService orderReliabilityService;
    private final OrderReliabilityProperties properties;

    @GetMapping("/anomalies")
    public Result anomalies(@RequestParam(value = "limit", defaultValue = "100") Integer limit) {
        Result denied = checkAdmin();
        if (denied != null) {
            return denied;
        }
        return Result.ok(orderReliabilityService.listAnomalies(normalizeLimit(limit)));
    }

    @PostMapping("/reconcile")
    public Result reconcile() {
        Result denied = checkAdmin();
        if (denied != null) {
            return denied;
        }
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(properties.getReconcileTimeoutSeconds());
        int reviewed = orderReliabilityService.reconcileBatch(
                cutoff, properties.getReconcileBatchSize());
        return Result.ok(reviewed);
    }

    @PostMapping("/{orderId}/replay")
    public Result replay(@PathVariable Long orderId,
                         @RequestBody(required = false) VoucherOrderRecoveryRequest request) {
        Result denied = checkAdmin();
        if (denied != null) {
            return denied;
        }
        if (request == null) {
            return Result.fail("requestId 和 reason 不能为空");
        }
        return orderReliabilityService.replay(orderId, request.getRequestId(), request.getReason(),
                UserHolder.getUser().getId());
    }

    @PostMapping("/{orderId}/refund")
    public Result refund(@PathVariable Long orderId,
                         @RequestBody(required = false) VoucherOrderRecoveryRequest request) {
        Result denied = checkAdmin();
        if (denied != null) {
            return denied;
        }
        if (request == null) {
            return Result.fail("requestId 和 reason 不能为空");
        }
        return orderReliabilityService.refund(orderId, request.getRequestId(), request.getReason(),
                UserHolder.getUser().getId());
    }

    private Result checkAdmin() {
        UserDTO user = UserHolder.getUser();
        if (!properties.isAdminEnabled()) {
            return Result.fail("管理员修复接口未启用");
        }
        if (user == null || user.getId() == null
                || properties.getAdminUserIds() == null
                || !properties.getAdminUserIds().contains(user.getId())) {
            return Result.fail("无权执行订单修复操作");
        }
        return null;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 100;
        }
        return Math.max(1, Math.min(limit, 500));
    }
}
