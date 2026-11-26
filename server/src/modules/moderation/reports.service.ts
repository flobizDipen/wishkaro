import { ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import type { ReportContentRequest } from './reports.schemas';

/**
 * Stores a user's report of generated content (FR-POS-12, FR-SET-07).
 *
 * Covers **both** surfaces — a poster and a wish draft — because §10 rule 7's "with user
 * reporting" is in the AI section rather than the poster section, and building it for
 * posters only would leave the feature already in users' hands without the mechanism Play
 * requires. `docs/M6_PLAN.md` §5.
 *
 * ## What it deliberately does not do
 *
 * **It does not delete anything.** A report is evidence; acting on it is a human decision
 * and there is nobody on the other end of this table yet. Deleting the poster on report
 * would also destroy the only copy of the thing being complained about.
 *
 * **It does not log the text or the detail.** §11 bans PII in logs, and both fields are
 * prose about a named person — the detail especially, because somebody reporting an
 * insensitive remembrance poster is writing about a death in a free-text box.
 */
@Injectable()
export class ReportsService {
  private readonly logger = new Logger(ReportsService.name);

  constructor(private readonly prisma: PrismaService) {}

  async report(userId: string, request: ReportContentRequest): Promise<{ id: string }> {
    /**
     * Ownership is checked before the row is written.
     *
     * Without it, `POST /v1/ai/report` with a guessed uuid would confirm whether that poster
     * exists — an enumeration oracle on other people's media, reachable by anybody who can
     * sign in. Returning 403 for both "not yours" and "no such thing" is the same decision
     * `MediaController` took for a bad signature versus an expired one.
     */
    if (request.posterId) {
      const poster = await this.prisma.poster.findFirst({
        where: { id: request.posterId, userId },
        select: { id: true },
      });
      if (!poster) {
        throw new ForbiddenException({ code: 'NOT_OWNED', message: 'No such poster' });
      }
    }
    if (request.generationId) {
      const generation = await this.prisma.generation.findFirst({
        where: { id: request.generationId, userId },
        select: { id: true },
      });
      if (!generation) {
        throw new ForbiddenException({ code: 'NOT_OWNED', message: 'No such generation' });
      }
    }

    const row = await this.prisma.contentReport.create({
      data: {
        userId,
        feature: request.feature,
        posterId: request.posterId ?? null,
        generationId: request.generationId ?? null,
        reason: request.reason,
        detail: request.detail ?? null,
        reportedText: request.reportedText ?? null,
      },
      select: { id: true },
    });

    // Ids and a category only. Never `detail`, never `reportedText`.
    this.logger.warn(`Content reported: ${request.feature} / ${request.reason} (report ${row.id})`);
    return row;
  }
}
