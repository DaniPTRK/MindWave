# MindWave Ethics & Privacy Charter (Server-side)

The MindWave backend is built around three non-negotiable rules:

## 1. No raw biometric data ever reaches the server
There is intentionally **no table** in the schema for storing raw HRV / EDA /
TEMP / ACC samples or per-user predictions. The only thing that crosses
the network from a user's device to the server is **model weight tensors**
sent through the Flower client (Federated Learning). All Stage-3 Room
tables on the phone (`StressReading`, `XaiExplanation`, `EmotionalJournal`,
`ContextEvent`) are device-local and never synchronized to the cloud.

## 2. K-anonymity on every HR-facing aggregate (k ≥ 5)
The `aggregate_stress_stats` table carries a hard CHECK constraint:

```sql
n_users >= 5
```

Pydantic v2 also rejects requests below this threshold at the API edge.
This means an HR persona ("Elena") can never see a row that aggregates
fewer than 5 employees — preventing single-employee inference even if a
department is small.

## 3. Minimal JWT claims, role-segregated access
JWTs carry only `sub` (user id), `role` and `org_id`. There are three roles:

| Role | Can do |
|---|---|
| `user` | Authenticate, fetch the latest global model, post their *own* org's k-anon aggregate batch |
| `hr` | Read aggregate stats — *only for their own organization* |
| `admin` | Register users, read everything |

Cross-tenant access is blocked at the router level (`if user.organization_id != target_org`).

## GDPR alignment
* **Lawful basis**: explicit consent + contract (employee dashboard).
* **Data minimization**: only weights + aggregates leave the device.
* **Right to be forgotten**: deleting a `User` row does not delete any of
  their biometric data, because there isn't any. Aggregate stats remain
  k-anonymous after the deletion by construction.
* **Purpose limitation**: aggregate stats may only be used to flag
  systemic issues (e.g., a stressed department), never to evaluate or
  discipline individuals — enforce this in the data-processing agreement.

