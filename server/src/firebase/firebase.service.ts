import { Inject, Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { cert, getApps, initializeApp, type App } from 'firebase-admin/app';
import { getMessaging, type Messaging } from 'firebase-admin/messaging';
import { APP_ENV, type AppEnv } from '../config/env';
import { resolveFirebaseCredential } from './firebase-credentials';

export interface PushResult {
  token: string;
  success: boolean;
  /** Set when the token is permanently dead and should be deleted. */
  unregistered: boolean;
  error?: string;
}

const UNREGISTERED_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

@Injectable()
export class FirebaseService implements OnModuleInit {
  private readonly logger = new Logger(FirebaseService.name);
  private app?: App;

  constructor(@Inject(APP_ENV) private readonly env: AppEnv) {}

  onModuleInit(): void {
    const { source, serviceAccount } = resolveFirebaseCredential({
      firebaseServiceAccount: this.env.FIREBASE_SERVICE_ACCOUNT,
      googleApplicationCredentials: this.env.GOOGLE_APPLICATION_CREDENTIALS,
    });

    if (serviceAccount.project_id !== this.env.FIREBASE_PROJECT_ID) {
      // Not fatal, but it is almost always a copy-paste error and it fails silently at send
      // time with a confusing permission error.
      this.logger.warn(
        `Service account is for project "${serviceAccount.project_id}" but ` +
          `FIREBASE_PROJECT_ID is "${this.env.FIREBASE_PROJECT_ID}". Pushes will fail.`,
      );
    }

    this.app =
      getApps().find((a) => a.name === 'wishkaro') ??
      initializeApp(
        {
          credential: cert({
            projectId: serviceAccount.project_id,
            clientEmail: serviceAccount.client_email,
            privateKey: serviceAccount.private_key,
          }),
          projectId: serviceAccount.project_id,
        },
        'wishkaro',
      );

    this.logger.log(
      `Firebase Admin ready — project ${serviceAccount.project_id}, credentials from ${source}`,
    );
  }

  private messaging(): Messaging {
    if (!this.app) throw new Error('FirebaseService used before onModuleInit');
    return getMessaging(this.app);
  }

  /**
   * Sends a DATA-ONLY high-priority message.
   *
   * Data-only is deliberate: a `notification` payload is rendered by the system when the app
   * is backgrounded and never reaches our handler, which would make `dedupe_key` suppression
   * and the deep link impossible. The client builds the notification itself.
   */
  async sendData(tokens: string[], data: Record<string, string>): Promise<PushResult[]> {
    if (tokens.length === 0) return [];

    const response = await this.messaging().sendEachForMulticast({
      tokens,
      data,
      android: { priority: 'high' },
      apns: {
        headers: { 'apns-push-type': 'background', 'apns-priority': '5' },
        payload: { aps: { contentAvailable: true } },
      },
    });

    return response.responses.map((r, i) => {
      const token = tokens[i] as string;
      if (r.success) return { token, success: true, unregistered: false };
      const code = r.error?.code ?? 'unknown';
      return {
        token,
        success: false,
        unregistered: UNREGISTERED_CODES.has(code),
        error: `${code}: ${r.error?.message ?? 'unknown error'}`,
      };
    });
  }
}
