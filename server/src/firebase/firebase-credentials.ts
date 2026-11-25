import { readFileSync } from 'node:fs';

/**
 * Firebase Admin credentials must load two different ways, and both are load-bearing:
 *
 *   - `GOOGLE_APPLICATION_CREDENTIALS` — a path to a JSON file. Local development, where the
 *     service-account key lives outside the repo (the repo is public).
 *   - `FIREBASE_SERVICE_ACCOUNT` — the JSON itself, in an environment variable. Railway and
 *     most other hosts have no filesystem to point at.
 *
 * The env var wins when both are set, so a deployed environment can never accidentally pick up
 * a stale path baked into an image. Built now rather than on deploy day, because discovering
 * this at the last step of M0 is what stalls the milestone.
 */

export interface ServiceAccountJson {
  project_id: string;
  client_email: string;
  private_key: string;
}

export type CredentialSource = 'FIREBASE_SERVICE_ACCOUNT' | 'GOOGLE_APPLICATION_CREDENTIALS';

export interface ResolvedCredential {
  source: CredentialSource;
  serviceAccount: ServiceAccountJson;
}

export class FirebaseCredentialError extends Error {}

export interface CredentialInput {
  firebaseServiceAccount?: string | undefined;
  googleApplicationCredentials?: string | undefined;
  /** Injectable for tests. */
  readFile?: (path: string) => string;
}

export function resolveFirebaseCredential(input: CredentialInput): ResolvedCredential {
  const readFile = input.readFile ?? ((p: string) => readFileSync(p, 'utf8'));

  const raw = input.firebaseServiceAccount?.trim();
  if (raw) {
    return {
      source: 'FIREBASE_SERVICE_ACCOUNT',
      serviceAccount: parseServiceAccount(decodeMaybeBase64(raw), 'FIREBASE_SERVICE_ACCOUNT'),
    };
  }

  const path = input.googleApplicationCredentials?.trim();
  if (path) {
    let contents: string;
    try {
      contents = readFile(path);
    } catch (cause) {
      throw new FirebaseCredentialError(
        `GOOGLE_APPLICATION_CREDENTIALS points at ${path}, which could not be read: ` +
          `${cause instanceof Error ? cause.message : String(cause)}`,
      );
    }
    return {
      source: 'GOOGLE_APPLICATION_CREDENTIALS',
      serviceAccount: parseServiceAccount(contents, `the file at ${path}`),
    };
  }

  throw new FirebaseCredentialError(
    'No Firebase credentials. Set FIREBASE_SERVICE_ACCOUNT (raw JSON) or ' +
      'GOOGLE_APPLICATION_CREDENTIALS (absolute path to the JSON file).',
  );
}

/** Hosts' secret editors mangle multi-line JSON, so base64 is a common workaround. Accept both. */
function decodeMaybeBase64(value: string): string {
  if (value.startsWith('{')) return value;
  try {
    const decoded = Buffer.from(value, 'base64').toString('utf8').trim();
    if (decoded.startsWith('{')) return decoded;
  } catch {
    // fall through to the JSON error below
  }
  return value;
}

function parseServiceAccount(contents: string, origin: string): ServiceAccountJson {
  let parsed: unknown;
  try {
    parsed = JSON.parse(contents);
  } catch (cause) {
    throw new FirebaseCredentialError(
      `${origin} is not valid JSON: ${cause instanceof Error ? cause.message : String(cause)}`,
    );
  }

  if (!parsed || typeof parsed !== 'object') {
    throw new FirebaseCredentialError(`${origin} did not contain a JSON object`);
  }

  const o = parsed as Record<string, unknown>;
  const missing = (['project_id', 'client_email', 'private_key'] as const).filter(
    (k) => typeof o[k] !== 'string' || !(o[k] as string).length,
  );
  if (missing.length) {
    throw new FirebaseCredentialError(`${origin} is missing required field(s): ${missing.join(', ')}`);
  }

  return {
    project_id: o.project_id as string,
    client_email: o.client_email as string,
    // Env vars cannot hold real newlines, so the key arrives with literal "\n" sequences.
    private_key: (o.private_key as string).replace(/\\n/g, '\n'),
  };
}
