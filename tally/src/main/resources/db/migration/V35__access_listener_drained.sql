-- Slice 2 PR-9 (ADR-028): this release removes the in-process access module, and with it the listener
-- com.tally.access.application.AccessService.on(com.tally.subscription.SubscriptionChanged). A listener is removed
-- only when it has no incomplete publication: Spring Modulith would mark one FAILED and never run it again
-- (ListenerGoneIT). This check stops the deploy while one is left, before V36 drops anything. The previous release,
-- which still has the listener, then completes it; deploy again after. A publication that reaches the listener after
-- this check shows in tally_events_listener_gone, and EventListenerGone pages (runbook events-stuck.md).
DO $$
DECLARE
    waiting bigint;
BEGIN
    SELECT count(*) INTO waiting
    FROM public.event_publication
    WHERE listener_id = 'com.tally.access.application.AccessService.on(com.tally.subscription.SubscriptionChanged)'
      AND completion_date IS NULL;
    IF waiting > 0 THEN
        RAISE EXCEPTION 'The listener com.tally.access.application.AccessService.on(com.tally.subscription.SubscriptionChanged) has % incomplete publication(s). ADR-028: let the previous release complete them, then deploy again.', waiting;
    END IF;
END $$;
