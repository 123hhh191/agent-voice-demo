-- Apply during the documented stopped-ingress deployment; retain this column on rollback.
ALTER TABLE voice_turn ADD COLUMN processing_deadline_at TIMESTAMP(6) NULL,
  ADD KEY idx_voice_processing_expiry (state, processing_deadline_at);
-- Bound legacy PROCESSING rows; production deployment must approve their count first.
UPDATE voice_turn SET processing_deadline_at=CURRENT_TIMESTAMP(6)+INTERVAL 10 SECOND
WHERE state='PROCESSING' AND processing_deadline_at IS NULL;
