-- Impedisce a livello DB che la stessa transazione bancaria (externalId del provider) venga importata più volte
-- sullo stesso conto. Da applicare manualmente in produzione (ddl-auto=validate non gestisce gli indici).
--
-- 1. Duplicati esistenti con stesso (account_id, external_id): se ne tiene una sola riga — preferendo quella non
--    cancellata, poi quella categorizzata, poi la più vecchia. Le altre vengono spostate nel cestino (deleted = true)
--    e perdono external_id, così non violano l'indice ma restano ripristinabili dall'utente.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY account_id, external_id
               ORDER BY deleted ASC, (category_id IS NULL) ASC, created_at ASC NULLS LAST, id
           ) AS rn
    FROM transactions
    WHERE external_id IS NOT NULL
)
UPDATE transactions t
SET deleted     = true,
    deleted_at  = COALESCE(t.deleted_at, NOW()),
    external_id = NULL
FROM ranked r
WHERE t.id = r.id
  AND r.rn > 1;

-- 2. Indice univoco parziale: include anche le righe cancellate, coerente con la dedup dell'import che le considera.
CREATE UNIQUE INDEX IF NOT EXISTS uk_transaction_account_external_id
    ON transactions (account_id, external_id)
    WHERE external_id IS NOT NULL;
