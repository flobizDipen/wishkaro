import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { CurrentUserId } from '../auth/current-user.decorator';
import { PhotoService } from './photo.service';

/**
 * A person's photo (FR-PPL-04, and FR-POS-01's "the person's unmodified photo").
 *
 * Unscoped work, absorbed to make definition-of-done item 1 reachable — see
 * `photo.service.ts` and `docs/M6_PLAN.md` §3.1.
 *
 * The body is the image bytes themselves, with an `image/jpeg`, `image/png` or `image/webp`
 * content type. `configure-app.ts` registers the raw parser for exactly those three types,
 * so nothing else on the API changes shape.
 */
@Controller('v1/people/:personId/photo')
export class PhotosController {
  constructor(private readonly photos: PhotoService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  upload(
    @CurrentUserId() userId: string,
    @Param('personId') personId: string,
    /**
     * A `Buffer` when the content type matched the raw parser's list, and `{}` when it did
     * not — Express hands the JSON parser's empty object through. `PhotoService` turns the
     * latter into a 400 that names the content types, rather than a crash inside `sharp`.
     */
    @Body() body: Buffer,
  ): Promise<{ assetId: string; url: string | null }> {
    return this.photos.upload(userId, personId, Buffer.isBuffer(body) ? body : undefined);
  }

  @Get()
  async current(
    @CurrentUserId() userId: string,
    @Param('personId') personId: string,
  ): Promise<{ url: string | null }> {
    return { url: await this.photos.currentUrl(userId, personId) };
  }

  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(
    @CurrentUserId() userId: string,
    @Param('personId') personId: string,
  ): Promise<void> {
    await this.photos.remove(userId, personId);
  }
}
