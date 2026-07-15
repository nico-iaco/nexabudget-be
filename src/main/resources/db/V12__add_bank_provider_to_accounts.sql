-- Aggiunge il discriminatore di provider bancario ad accounts, necessario per l'integrazione
-- Enable Banking parallela a GoCardless (colonne requisition_id/external_account_id già esistenti
-- e riusate da entrambi i provider). Le righe già collegate tramite il flusso GoCardless esistente
-- vengono valorizzate a GOCARDLESS; i conti mai collegati restano con provider = NULL.
ALTER TABLE accounts ADD COLUMN provider VARCHAR(32);

UPDATE accounts SET provider = 'GOCARDLESS'
WHERE requisition_id IS NOT NULL OR external_account_id IS NOT NULL;
