import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decodeFirebaseConfig } from './firebase-config.mjs';

const client = packageName => ({ client_info: { android_client_info: { package_name: packageName } } });
const config = {
  project_info: { project_id: 'fixture', project_number: '1234' },
  client: [client('com.gimso2x.herdr'), client('com.gimso2x.herdr.debug')],
};
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64');

test('retains the client data for both installed variants', () => {
  assert.deepEqual(decodeFirebaseConfig(encode(config)), config);
});

test('refuses a missing variant before invoking the Android build', () => {
  assert.throws(() => decodeFirebaseConfig(encode({ ...config, client: [config.client[0]] })));
  assert.throws(() => decodeFirebaseConfig(encode({ ...config, client: [config.client[1]] })));
});

test('never accepts a server signing key as client configuration', () => {
  assert.throws(() => decodeFirebaseConfig(encode({ ...config, type: 'service_account', private_key: 'fixture' })));
});

test('rejects malformed, missing and oversized input without echoing it', () => {
  for (const value of [undefined, '', 'not!base64', Buffer.from('{broken').toString('base64'), 'a'.repeat(100_001)]) {
    assert.throws(() => decodeFirebaseConfig(value));
  }
});
