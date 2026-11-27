import {
  Controller,
  ForbiddenException,
  Get,
  Inject,
  NotFoundException,
  Param,
  Query,
  Res,
} from '@nestjs/common';
import type { Response } from 'express';
import { Public } from '../auth/public.decorator';
import { BLOB_STORE, type BlobStore } from './blob-store';
import { LocalBlobStore } from './local-blob-store';

/**
 * Serves bytes behind a short-lived signature (§11: "R2 objects served via short-lived signed
 * URLs").
 *
 * **`@Public`, and the signature is the authorisation.** That is not a weakening: the URL is
 * handed to WhatsApp or a share sheet, which fetch it as themselves with no WishKaro session
 * and no header we control. A route that required a bearer token would be a route no
 * receiving app could load, which is the whole reason signed URLs exist as a pattern.
 *
 * What makes it safe is what the signature covers — the key AND the expiry, so neither can be
 * edited without invalidating the other — and how long it lives, which is ten minutes. A URL
 * that leaks into a log or a bug report is dead before anybody reads it.
 *
 * Only reachable when the store is local. An R2 deployment signs at R2 and the phone never
 * comes here at all.
 */
@Controller('v1/media')
export class MediaController {
  constructor(@Inject(BLOB_STORE) private readonly store: BlobStore) {}

  @Public()
  @Get(':key')
  async serve(
    @Param('key') key: string,
    @Query('expires') expires: string,
    @Query('sig') sig: string,
    @Res() res: Response,
  ): Promise<void> {
    if (!(this.store instanceof LocalBlobStore)) {
      throw new NotFoundException({ code: 'NOT_FOUND', message: 'No such media route' });
    }

    /**
     * One message for a bad signature and for an expired one, deliberately.
     *
     * Telling a caller which of the two it was tells them the key is real, which is the one
     * fact the signature exists to keep from somebody holding a guess.
     */
    if (!this.store.verify(key, Number(expires), sig ?? '')) {
      throw new ForbiddenException({ code: 'MEDIA_URL_INVALID', message: 'Link expired' });
    }

    const body = await this.store.get(key);
    if (!body) {
      throw new NotFoundException({ code: 'NOT_FOUND', message: 'No such media' });
    }

    // `private` and `no-store`: a poster is a picture of a named person and the URL is a
    // capability. A shared cache holding it would outlive the signature that gated it.
    res.setHeader('Content-Type', contentTypeFor(key));
    res.setHeader('Cache-Control', 'private, no-store');
    res.setHeader('Content-Length', String(body.byteLength));
    res.end(body);
  }
}

/**
 * Derived from the key's suffix, which this app controls — every key is minted by
 * `blobKeyFor()` and ends in a known extension.
 *
 * Deliberately NOT sniffed from the bytes: a type read out of user-supplied content is how a
 * file that claims to be a PNG gets served as something a browser will execute. An unknown
 * suffix becomes `application/octet-stream`, which renders nothing and runs nothing.
 */
function contentTypeFor(key: string): string {
  if (key.endsWith('.png')) return 'image/png';
  if (key.endsWith('.jpg') || key.endsWith('.jpeg')) return 'image/jpeg';
  if (key.endsWith('.webp')) return 'image/webp';
  return 'application/octet-stream';
}
