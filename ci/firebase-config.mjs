import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

export function decodeFirebaseConfig(encoded) {
  if (!encoded || encoded.length > 100_000 || !/^[A-Za-z0-9+/=\s]+$/.test(encoded)) {
    throw new Error('HERDR_GOOGLE_SERVICES_B64 is missing or invalid');
  }
  let config;
  try {
    config = JSON.parse(Buffer.from(encoded, 'base64').toString('utf8'));
  } catch {
    throw new Error('Firebase client configuration is not valid JSON');
  }
  if (config.type === 'service_account' || config.private_key || config.private_key_id) {
    throw new Error('A server service account must never be used in an Android build');
  }
  const packages = new Set(config.client?.map(client => client.client_info?.android_client_info?.package_name));
  if (!config.project_info?.project_id || !config.project_info?.project_number ||
      !packages.has('com.gimso2x.herdr') || !packages.has('com.gimso2x.herdr.debug')) {
    throw new Error('Firebase client configuration must include both herdr Android packages');
  }
  return config;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const config = decodeFirebaseConfig(process.env.HERDR_GOOGLE_SERVICES_B64);
    const output = process.argv[2] ?? 'app/google-services.json';
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, JSON.stringify(config), { mode: 0o600 });
    console.log('Firebase client configuration installed for both Android variants');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
