-- Run this once on an existing hmdp database after checking for duplicates.
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

ALTER TABLE tb_voucher_order
    ADD UNIQUE INDEX uk_voucher_order_user_voucher (user_id, voucher_id);
