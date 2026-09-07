-- Run this migration once against the hmdp database before enabling reconciliation.
-- It only creates the reliability tables and does not modify existing orders.

CREATE TABLE IF NOT EXISTS `tb_voucher_order_process` (
  `order_id` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，与消息和预扣记录一致',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `publish_status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `process_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `failure_reason` varchar(255) DEFAULT NULL,
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `reserved_at` datetime NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_process_status_update` (`process_status`, `update_time`),
  KEY `idx_process_user_voucher` (`user_id`, `voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单可靠性处理记录';

CREATE TABLE IF NOT EXISTS `tb_voucher_order_recovery_audit` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL COMMENT '修复请求幂等号',
  `order_id` bigint(20) UNSIGNED NOT NULL,
  `action` varchar(16) NOT NULL COMMENT 'REPLAY or REFUND',
  `result` varchar(16) NOT NULL DEFAULT 'PENDING',
  `reason` varchar(255) NOT NULL,
  `operator_id` bigint(20) UNSIGNED DEFAULT NULL,
  `details` varchar(255) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recovery_request_id` (`request_id`),
  KEY `idx_recovery_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单人工修复审计';

CREATE TABLE IF NOT EXISTS `tb_voucher_order_dead_letter` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `message_id` varchar(128) NOT NULL COMMENT 'RabbitMQ message id',
  `order_id` bigint(20) UNSIGNED DEFAULT NULL,
  `payload` longtext NOT NULL,
  `death_reason` varchar(64) NOT NULL DEFAULT 'UNKNOWN',
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'ARCHIVED',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dead_letter_message_id` (`message_id`),
  KEY `idx_dead_letter_order_id` (`order_id`),
  KEY `idx_dead_letter_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单死信归档';
