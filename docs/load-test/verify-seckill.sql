-- Replace :voucher_id and :test_user_min/:test_user_max with the isolated test data.
-- Run after the async order queue has drained.

SELECT voucher_id, stock
FROM tb_seckill_voucher
WHERE voucher_id = :voucher_id;

SELECT COUNT(*) AS final_orders
FROM tb_voucher_order
WHERE voucher_id = :voucher_id
  AND user_id BETWEEN :test_user_min AND :test_user_max;

SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
WHERE voucher_id = :voucher_id
  AND user_id BETWEEN :test_user_min AND :test_user_max
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

SELECT process_status, COUNT(*) AS process_count
FROM tb_voucher_order_process
WHERE voucher_id = :voucher_id
GROUP BY process_status;

SELECT COUNT(*) AS archived_dead_letters
FROM tb_voucher_order_dead_letter
WHERE order_id IN (
    SELECT order_id
    FROM tb_voucher_order_process
    WHERE voucher_id = :voucher_id
);
