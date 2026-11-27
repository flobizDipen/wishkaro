import { BadRequestException, Controller, Get, Header, Query, Res } from '@nestjs/common';
import type { Response } from 'express';
import { CurrentUserId } from '../auth/current-user.decorator';
import { ExportService } from './export.service';

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
  constructor(private readonly exports: ExportService) {}

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
}
