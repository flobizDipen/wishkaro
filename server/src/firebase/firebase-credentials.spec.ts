import { FirebaseCredentialError, resolveFirebaseCredential } from './firebase-credentials';

const VALID = {
  project_id: 'wishkaro-1c95e',
  client_email: 'svc@wishkaro-1c95e.iam.gserviceaccount.com',
  private_key: '-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\\n',
};
const VALID_JSON = JSON.stringify(VALID);

describe('resolveFirebaseCredential — both paths must work (hard constraint)', () => {
  it('reads raw JSON from FIREBASE_SERVICE_ACCOUNT', () => {
    const r = resolveFirebaseCredential({ firebaseServiceAccount: VALID_JSON });
    expect(r.source).toBe('FIREBASE_SERVICE_ACCOUNT');
    expect(r.serviceAccount.project_id).toBe('wishkaro-1c95e');
  });

  it('reads a file from GOOGLE_APPLICATION_CREDENTIALS', () => {
    const r = resolveFirebaseCredential({
      googleApplicationCredentials: '/secrets/sa.json',
      readFile: (p) => {
        expect(p).toBe('/secrets/sa.json');
        return VALID_JSON;
      },
    });
    expect(r.source).toBe('GOOGLE_APPLICATION_CREDENTIALS');
    expect(r.serviceAccount.client_email).toBe(VALID.client_email);
  });

  it('prefers the env var when both are set', () => {
    const r = resolveFirebaseCredential({
      firebaseServiceAccount: VALID_JSON,
      googleApplicationCredentials: '/secrets/stale.json',
      readFile: () => {
        throw new Error('must not read the file when the env var is present');
      },
    });
    expect(r.source).toBe('FIREBASE_SERVICE_ACCOUNT');
  });

  it('accepts base64, because secret editors mangle multi-line JSON', () => {
    const b64 = Buffer.from(VALID_JSON, 'utf8').toString('base64');
    const r = resolveFirebaseCredential({ firebaseServiceAccount: b64 });
    expect(r.serviceAccount.project_id).toBe('wishkaro-1c95e');
  });

  it('turns literal \\n sequences in the private key back into newlines', () => {
    const r = resolveFirebaseCredential({ firebaseServiceAccount: VALID_JSON });
    expect(r.serviceAccount.private_key).toContain('\n');
    expect(r.serviceAccount.private_key).not.toContain('\\n');
  });

  it('ignores an empty env var and falls through to the file', () => {
    const r = resolveFirebaseCredential({
      firebaseServiceAccount: '   ',
      googleApplicationCredentials: '/secrets/sa.json',
      readFile: () => VALID_JSON,
    });
    expect(r.source).toBe('GOOGLE_APPLICATION_CREDENTIALS');
  });
});

describe('resolveFirebaseCredential — failures say what to do', () => {
  it('fails when neither is set', () => {
    expect(() => resolveFirebaseCredential({})).toThrow(FirebaseCredentialError);
    expect(() => resolveFirebaseCredential({})).toThrow(/FIREBASE_SERVICE_ACCOUNT/);
  });

  it('fails with the path when the file is unreadable', () => {
    expect(() =>
      resolveFirebaseCredential({
        googleApplicationCredentials: '/nope/sa.json',
        readFile: () => {
          throw new Error('ENOENT');
        },
      }),
    ).toThrow(/\/nope\/sa\.json/);
  });

  it('fails on malformed JSON', () => {
    expect(() => resolveFirebaseCredential({ firebaseServiceAccount: '{not json' })).toThrow(
      /not valid JSON/,
    );
  });

  it('names the missing fields', () => {
    expect(() =>
      resolveFirebaseCredential({ firebaseServiceAccount: JSON.stringify({ project_id: 'x' }) }),
    ).toThrow(/client_email, private_key/);
  });
});
