import assert from 'node:assert/strict';

export function domainBindingRequest(changeset, hostname, zoneId) {
  assert.equal(changeset.conflicting.length, 0, 'Test hostname conflicts with an existing DNS record');
  assert.equal(changeset.removed.length, 0, 'Domain plan would remove an existing binding');
  assert.equal(changeset.updated.length, 0, 'Domain plan would change an existing binding');
  assert.equal(changeset.added.length, 1);
  assert.equal(changeset.added[0].hostname, hostname);
  return {
    origins: [{ hostname, zone_id: zoneId, enabled: true, previews_enabled: false }],
    override_scope: false,
    override_existing_origin: false,
    override_existing_dns_record: false,
  };
}
