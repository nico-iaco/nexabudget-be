-- Timestamp di acquisizione del lock di sincronizzazione bancaria (accounts.is_synchronizing).
-- Il lock veniva rilasciato solo nel finally del sync: un crash/redeploy durante il sync lo lasciava
-- bloccato per sempre. Ora un lock più vecchio del timeout (AccountService.SYNC_LOCK_TIMEOUT, 1h),
-- o senza timestamp, è considerato orfano e viene ripreso dal sync successivo.
-- Da applicare manualmente in produzione (ddl-auto=validate).
ALTER TABLE accounts ADD COLUMN sync_started_at TIMESTAMP;
