import assert from 'node:assert/strict';
import test from 'node:test';
import { domainBindingRequest } from './domain-plan.mjs';

const hostname = 'authbox-test.example.com';
const plan = () => ({ added: [{ hostname }], removed: [], updated: [], conflicting: [] });

test('仅创建指定域名，关闭全部覆盖与预览选项', () => {
  const body = domainBindingRequest(plan(), hostname, 'test-zone');
  assert.deepEqual(body, {
    origins: [{ hostname, zone_id: 'test-zone', enabled: true, previews_enabled: false }],
    override_scope: false, override_existing_origin: false, override_existing_dns_record: false,
  });
});

for (const [description, change] of [
  ['已有 DNS 记录', (value) => value.conflicting.push({ hostname })],
  ['其他 Worker 已绑定', (value) => value.updated.push({ hostname, service: 'existing-worker' })],
  ['计划删除已有绑定', (value) => value.removed.push({ hostname: 'existing.example.com' })],
  ['计划夹带另一个域名', (value) => value.added.push({ hostname: 'extra.example.com' })],
  ['计划返回错误域名', (value) => { value.added[0].hostname = 'wrong.example.com'; }],
  ['没有创建目标域名', (value) => { value.added.length = 0; }],
  ['服务端省略冲突字段', (value) => { delete value.conflicting; }],
]) {
  test(`拒绝${description}`, () => {
    const value = plan();
    change(value);
    assert.throws(() => domainBindingRequest(value, hostname, 'test-zone'));
  });
}
