import { Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import {
  eventsCsv,
  peopleCsv,
  wishesCsv,
  type ExportEventRow,
  type ExportPersonRow,
  type ExportWishRow,
} from './export-csv';

/**
 * FR-SET-03 — *"Export my data: full JSON + CSV of people, events, history"* — and §12's
 * "data export (DPDP access right)".
 *
 * ## What this builds, and what it deliberately does not
 *
 * It **produces** the export. It does not **deliver** it, and that distinction is
 * `docs/M9_PLAN.md`'s decision 2: FR-SET-03 says "delivered by email link" and this app
 * cannot send email — there is no SMTP, no provider, no key and no dependency that could.
 * The alternative already built is the blob store's short-lived signed URL, which is how
 * posters already leave the server and needs no new vendor; it is also a weaker reading of
 * the requirement, because an email link survives the app being uninstalled, which is exactly
 * the moment somebody wants their data.
 *
 * Both readings need the bytes produced, which is this file. Neither is chosen here.
 *
 * ## What is in it
 *
 * Everything in this database that belongs to the user and is theirs to read back:
 * people, their traits, every event of every type, wish history, poster and photo metadata,
 * and their settings. Tombstoned rows are **included and marked**, because a person the user
 * deleted thirty days ago is still inside FR-PPL-08's restore window and is still data we
 * hold about them — a DPDP access request is about what is held, not about what is currently
 * displayed.
 *
 * ## What is not in it
 *
 * - **Bytes.** Photographs and posters are files; the export lists them with their dates and
 *   retention state, not their contents. A JSON document with 566 base64 JPEGs in it is not
 *   something a phone can build, hold or send.
 * - **`ai_usage` and `generation`.** They record what a model call cost us. They contain no
 *   user content at all — no prompt text, no name — and an access right is a right to the
 *   data about you, not to our ledger. Named here so the omission is a decision somebody can
 *   disagree with rather than something nobody noticed.
 * - **Secrets of any kind.** No tokens, no FCM tokens, no `google_sub`. An export is a file
 *   that leaves the system, and a device token in it is a capability to push to a phone.
 */

/** Bumped when the shape changes, so a file can be read years later by something that cares. */
export const EXPORT_FORMAT_VERSION = 1;

export interface ExportBundle {
  json: unknown;
  files: { name: string; contentType: string; body: string }[];
}

@Injectable()
export class ExportService {
  constructor(private readonly prisma: PrismaService) {}

  /**
   * The email on the account, for FR-AUTH-08's typed confirmation.
   *
   * Here rather than in the controller so the controller holds no Prisma, and here rather than
   * in `AccountDeletionService` so that the thing which performs the deletion is not also the
   * thing that decides whether it was confirmed.
   */
  async emailOf(userId: string): Promise<string> {
    const user = await this.prisma.user.findFirst({
      where: { id: userId, deletedAt: null },
      select: { email: true },
    });
    if (!user) {
      throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such account' });
    }
    return user.email;
  }

  async build(userId: string, now: Date = new Date()): Promise<ExportBundle> {
    const user = await this.prisma.user.findFirst({ where: { id: userId, deletedAt: null } });
    if (!user) {
      // The caller is authenticated as this user, so this is a deleted account with a token
      // still inside its TTL — docs/M9_PLAN.md C8. An empty export would be worse than an
      // error: it reads as "we hold nothing about you", which is a different claim.
      throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such account' });
    }

    const [people, events, traits, wishes, posters, assets] = await Promise.all([
      this.prisma.person.findMany({
        where: { userId },
        orderBy: [{ displayName: 'asc' }, { id: 'asc' }],
      }),
      this.prisma.event.findMany({ where: { userId }, orderBy: [{ month: 'asc' }, { day: 'asc' }] }),
      this.prisma.personTrait.findMany({ where: { userId }, orderBy: { createdAt: 'asc' } }),
      this.prisma.wish.findMany({ where: { userId }, orderBy: { sentAt: 'desc' } }),
      this.prisma.poster.findMany({ where: { userId }, orderBy: { createdAt: 'desc' } }),
      this.prisma.asset.findMany({ where: { userId }, orderBy: { createdAt: 'desc' } }),
    ]);

    const nameOf = new Map(people.map((p) => [p.id, p.displayName]));

    const json = {
      format: 'wishkaro-export',
      formatVersion: EXPORT_FORMAT_VERSION,
      exportedAt: now.toISOString(),
      account: {
        email: user.email,
        name: user.name,
        timezone: user.timezone,
        locale: user.locale,
        createdAt: user.createdAt.toISOString(),
      },
      settings: {
        reminderHour: user.reminderHour,
        ladderD7: user.ladderD7,
        ladderD1: user.ladderD1,
        ladderD0: user.ladderD0,
        quietHoursStart: user.quietHoursStart,
        quietHoursEnd: user.quietHoursEnd,
        digestEnabled: user.digestEnabled,
        digestHour: user.digestHour,
      },
      people: people.map((p) => ({
        id: p.id,
        displayName: p.displayName,
        relationToOwner: p.relationToOwner,
        isSelf: p.isSelf,
        gender: p.gender,
        phone: p.phone,
        email: p.email,
        notes: p.notes,
        isDeceased: p.isDeceased,
        isMinor: p.isMinor,
        completenessScore: p.completenessScore,
        reminderHourOverride: p.reminderHourOverride,
        createdAt: p.createdAt.toISOString(),
        // Marked rather than filtered: FR-PPL-08's 30-day restore window means a tombstoned
        // person is still data we hold.
        deletedAt: p.deletedAt?.toISOString() ?? null,
        events: events
          .filter((e) => e.personId === p.id)
          .map((e) => ({
            id: e.id,
            type: e.type,
            sentiment: e.sentiment,
            day: e.day,
            month: e.month,
            year: e.year,
            label: e.label,
            isMuted: e.isMuted,
            calendarSystem: e.calendarSystem,
            deletedAt: e.deletedAt?.toISOString() ?? null,
          })),
        traits: traits
          .filter((t) => t.personId === p.id)
          .map((t) => ({
            kind: t.kind,
            value: t.value,
            source: t.source,
            deletedAt: t.deletedAt?.toISOString() ?? null,
          })),
      })),
      wishes: wishes.map((w) => ({
        personId: w.personId,
        personName: nameOf.get(w.personId) ?? null,
        eventType: w.eventType,
        occurrenceYear: w.occurrenceYear,
        sentiment: w.sentiment,
        messageText: w.messageText,
        tone: w.tone,
        language: w.language,
        channel: w.channel,
        source: w.source,
        posterId: w.posterId,
        sentAt: w.sentAt.toISOString(),
        deletedAt: w.deletedAt?.toISOString() ?? null,
      })),
      /**
       * Metadata, not bytes. A poster is a picture of a named person; what the user gets here
       * is the record that it exists, when it was made and when FR-SET-06 will reclaim it.
       */
      posters: posters.map((p) => ({
        id: p.id,
        personId: p.personId,
        personName: nameOf.get(p.personId) ?? null,
        themeId: p.themeId,
        family: p.family,
        sentiment: p.sentiment,
        eventType: p.eventType,
        format: p.format,
        createdAt: p.createdAt.toISOString(),
        deletedAt: p.deletedAt?.toISOString() ?? null,
      })),
      media: assets.map((a) => ({
        id: a.id,
        kind: a.kind,
        personId: a.personId,
        personName: a.personId ? (nameOf.get(a.personId) ?? null) : null,
        contentType: a.contentType,
        bytes: a.bytes,
        width: a.width,
        height: a.height,
        createdAt: a.createdAt.toISOString(),
        // FR-SET-06, surfaced where the user can see what is about to happen to their media.
        retention: a.retention,
        expiresAt: a.expiresAt?.toISOString() ?? null,
        purgeWarnedAt: a.purgeWarnedAt?.toISOString() ?? null,
        purgedAt: a.purgedAt?.toISOString() ?? null,
      })),
    };

    /**
     * `people.csv` carries live people with a live BIRTHDAY, and nothing else.
     *
     * Tombstoned rows are excluded here although they are in the JSON: this file's job is to
     * re-import cleanly, and re-importing somebody the user deleted would undo a deletion
     * they performed. The JSON is where "what is held" is answered in full.
     */
    const peopleRows: ExportPersonRow[] = [];
    for (const p of people) {
      if (p.deletedAt) continue;
      const birthday = events.find(
        (e) => e.personId === p.id && e.type === 'BIRTHDAY' && !e.deletedAt,
      );
      if (!birthday) continue;
      peopleRows.push({
        displayName: p.displayName,
        relationToOwner: p.relationToOwner,
        phone: p.phone,
        notes: p.notes,
        day: birthday.day,
        month: birthday.month,
        year: birthday.year,
      });
    }

    const eventRows: ExportEventRow[] = events
      .filter((e) => !e.deletedAt)
      .map((e) => ({
        displayName: nameOf.get(e.personId) ?? '',
        type: e.type,
        day: e.day,
        month: e.month,
        year: e.year,
        label: e.label,
        isMuted: e.isMuted,
      }));

    const wishRows: ExportWishRow[] = wishes
      .filter((w) => !w.deletedAt)
      .map((w) => ({
        displayName: nameOf.get(w.personId) ?? '',
        eventType: w.eventType,
        occurrenceYear: w.occurrenceYear,
        sentiment: w.sentiment,
        tone: w.tone,
        language: w.language,
        channel: w.channel,
        source: w.source,
        messageText: w.messageText,
        sentAt: w.sentAt,
      }));

    return {
      json,
      files: [
        { name: 'people.csv', contentType: 'text/csv; charset=utf-8', body: peopleCsv(peopleRows) },
        { name: 'events.csv', contentType: 'text/csv; charset=utf-8', body: eventsCsv(eventRows) },
        { name: 'wishes.csv', contentType: 'text/csv; charset=utf-8', body: wishesCsv(wishRows) },
      ],
    };
  }
}
