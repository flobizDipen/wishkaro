import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Header,
  HttpCode,
  HttpStatus,
  Query,
  Res,
  ServiceUnavailableException,
} from '@nestjs/common';
import type { Response } from 'express';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { AccountDeletionService, BlobDeletionIncomplete } from './account-deletion.service';
import { ExportService } from './export.service';

/**
 * FR-AUTH-08: deletion is *"irreversible, **confirmed by typing**"*.
 *
 * The server asks for it too, not only the screen. A confirmation that lives only in the UI is
 * a confirmation that a second client, a stale build or a curl does not have — and this is the
 * one endpoint in the app where the cost of being wrong cannot be undone.
 *
 * The email rather than a fixed word, because it proves the caller knows WHOSE account this
 * is. A token that has been picked up from somewhere else authenticates fine and does not know
 * that.
 */
const DeleteAccountSchema = z.object({
  confirmEmail: z.string().min(1, 'Type the email address on this account to confirm'),
});

/**
 * `GET /v1/account/export` — listed in `REQUIREMENTS.md` §9 since v1 and never built until
 * now (`docs/M9_PLAN.md` C6).
 *
 * ## Two routes rather than an archive
 *
 * FR-SET-03 asks for "full JSON **and** CSV". A zip would need a dependency this repository
 * does not have, for a file a phone is going to open one sheet of anyway. So: the JSON
 * document at `/v1/account/export`, and each CSV at `/v1/account/export.csv?dataset=…`.
 *
 * ## This is production, not delivery
 *
 * Both routes are authenticated, which every reading of FR-SET-03 needs — the bytes have to
 * be produced before they can be emailed or signed. **How the user receives the export is
 * decision 2 in `docs/M9_PLAN.md` and is not taken here**: email is a new vendor and a new
 * key (and makes definition-of-done item 9 false — C1), while a blob-store signed URL is the
 * path posters already take and needs nothing new. Nothing in this file forecloses either.
 *
 * ## No caching, anywhere
 *
 * `private, no-store` on both. The response body is the single most PII-dense artifact this
 * product can produce — on the owner's own account it is 566 people with their phone numbers,
 * their notes and the text of messages sent to them. A shared cache holding it, or a browser
 * writing it to disk, is a copy nobody is tracking.
 */
@Controller('v1/account')
export class AccountController {
  constructor(
    private readonly exports: ExportService,
    private readonly deletion: AccountDeletionService,
  ) {}

  @Get('export')
  @Header('Cache-Control', 'private, no-store')
  @Header('Content-Disposition', 'attachment; filename="wishkaro-export.json"')
  async exportJson(@CurrentUserId() userId: string): Promise<unknown> {
    const bundle = await this.exports.build(userId);
    return bundle.json;
  }

  @Get('export.csv')
  async exportCsv(
    @CurrentUserId() userId: string,
    @Query('dataset') dataset: string | undefined,
    @Res() res: Response,
  ): Promise<void> {
    const name = `${dataset ?? 'people'}.csv`;
    const bundle = await this.exports.build(userId);
    const file = bundle.files.find((f) => f.name === name);

    if (!file) {
      throw new BadRequestException({
        code: 'UNKNOWN_DATASET',
        message: `dataset must be one of: ${bundle.files.map((f) => f.name.replace('.csv', '')).join(', ')}`,
      });
    }

    res.setHeader('Content-Type', file.contentType);
    res.setHeader('Cache-Control', 'private, no-store');
    res.setHeader('Content-Disposition', `attachment; filename="wishkaro-${file.name}"`);
    res.end(file.body);
  }

  /**
   * `DELETE /v1/account` — listed in §9 since v1, built in M9.
   *
   * 204 and an empty body. There is nothing useful to return: the counts the service produces
   * are for the log and the tests, and handing a caller "we deleted 41 objects" tells them
   * something about an account that no longer exists.
   *
   * The public web page FR-SET-04 also requires is **not here** — it needs a stable URL and an
   * identity story of its own, and both are decision 1 in `docs/M9_PLAN.md`. An unauthenticated
   * deletion page is the single most attackable surface this app would have, so it is not being
   * put up before somebody has decided how it proves who is asking.
   */
  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteAccount(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(DeleteAccountSchema)) body: z.infer<typeof DeleteAccountSchema>,
  ): Promise<void> {
    const user = await this.exports.emailOf(userId);

    // Case-insensitive and trimmed: this is a confirmation that the user knows which account
    // they are destroying, not a password. Being strict about capitalisation here would turn a
    // safety check into an obstacle, and an obstacle is what people click past.
    if (user.toLowerCase() !== body.confirmEmail.trim().toLowerCase()) {
      throw new BadRequestException({
        code: 'CONFIRMATION_MISMATCH',
        message: 'That is not the email address on this account',
      });
    }

    try {
      await this.deletion.deleteAccount(userId);
    } catch (cause) {
      if (cause instanceof BlobDeletionIncomplete) {
        /**
         * 503 and a retryable code, not 500.
         *
         * Nothing was destroyed — the service stops before the transaction when it cannot
         * remove every object, precisely so that the rows still point at what is left and
         * repeating the request finishes the job. Telling the caller to try again is both true
         * and the correct instruction.
         */
        throw new ServiceUnavailableException({
          code: 'DELETION_INCOMPLETE',
          message: 'Your data could not be fully removed just now. Nothing was deleted — please try again.',
        });
      }
      throw cause;
    }
  }
}
